import static jakarta.ws.rs.core.Response.Status.Family.REDIRECTION;
import static jakarta.ws.rs.core.Response.Status.Family.SUCCESSFUL;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Locale.GERMANY;
import static java.util.Objects.requireNonNull;
import static net.fortuna.ical4j.model.Component.VEVENT;
import static net.fortuna.ical4j.model.Property.CLASS;
import static net.fortuna.ical4j.model.Property.CREATED;
import static net.fortuna.ical4j.model.Property.DESCRIPTION;
import static net.fortuna.ical4j.model.Property.DTSTAMP;
import static net.fortuna.ical4j.model.Property.DTSTART;
import static net.fortuna.ical4j.model.Property.LAST_MODIFIED;
import static net.fortuna.ical4j.model.Property.LOCATION;
import static net.fortuna.ical4j.model.Property.STATUS;
import static net.fortuna.ical4j.model.Property.SUMMARY;
import static net.fortuna.ical4j.model.Property.UID;
import static net.fortuna.ical4j.model.Property.URL;
import static social.bigbone.api.entity.data.Visibility.DIRECT;
import static social.bigbone.api.entity.data.Visibility.PRIVATE;
import static social.bigbone.api.entity.data.Visibility.PUBLIC;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.UriBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DateProperty;
import social.bigbone.MastodonClient;
import social.bigbone.api.exception.BigBoneRequestException;

/**
 * @author Markus KARG (markus@headcrashing.eu)
 */
public final class CindyBootstrap {
	private static final int MAXIMUM_MASTODON_MESSAGE_LENGTH = 500;
	private static final DateTimeFormatter GERMAN_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("d. LLLL yyyy HH:mm", GERMANY);
	private static final Logger LOGGER = Logger.getLogger(CindyBootstrap.class.getName());
	private static final ZoneId EUROPE_BERLIN = ZoneId.of("Europe/Berlin");

	public static void main(final String[] args) throws InterruptedException, ExecutionException {
		final var mastodonHost = System.getenv("CINDY_MASTODON_HOST");
		final var mastodonAccessToken = System.getenv("CINDY_MASTODON_ACCESS_TOKEN");
		final var lastRunFile = Path.of(System.getenv().getOrDefault("CINDY_LAST_RUN_FILE", "lastRun"));
		final var ipPort = Integer.parseInt(System.getenv().getOrDefault("CINDY_IP_PORT", "8080"));
		final var pollingInterval = 1_000 * Integer.parseInt(System.getenv().getOrDefault("CINDY_POLLING_SECONDS", "60"));
		final var sources = List.of(System.getenv().getOrDefault("CINDY_CALENDAR_SOURCES", "").split(",")).stream().map(String::trim).map(URI::create).toList();
		final var redirectionsLimit = Integer.parseInt(System.getenv().getOrDefault("CINDY_REDIRECTIONS_LIMIT", "50"));

		if (args.length > 0 && "--check-health".equals(args[0])) {
			final var ignoreErrors = args.length > 1 && "--ignore-errors".equals(args[1]);
			final var exitCode = checkHealth(ipPort, ignoreErrors).ordinal();
			System.exit(exitCode);
		}

		if (args.length == 0) {
			requireNonNull(mastodonHost, "CINDY_MASTODON_HOST is missing");
			requireNonNull(mastodonAccessToken, "CINDY_MASTODON_ACCESS_TOKEN is missing");
			if (sources.isEmpty())
				LOGGER.warning("CINDY_MASTODON_SOURCE is missing");
		}

		final var config = SeBootstrap.Configuration.builder().port(ipPort).build();
		SeBootstrap.start(CindyApplication.class, config).thenAccept(instance -> {
			instance.stopOnShutdown(stopResult -> {
				try {
					System.err.printf("Stop result: %s%n", stopResult);
				} catch (final Exception e) {
					e.printStackTrace();
				}
			});

			final var client = ClientBuilder.newClient()
				.register(FollowRedirects.class)
				.register(ICalendarMessageBodyReader.class);
			final var calendarSources = sources.stream().map(source -> new CalendarSource(source, client
					.target(source).request()
					.buildGet()
					.property(FollowRedirects.REDIRECTIONS_LIMIT, redirectionsLimit)))
					.toList();

			final var message = new StringBuilder(MAXIMUM_MASTODON_MESSAGE_LENGTH);

			/*
			 * AppCDS: Stop immediately once service is up and running
			 */
			if (args.length > 0 && "--stop".equals(args[0])) {
				instance.stop().thenRun(() -> System.exit(0));
				System.out.println("Stopping immediately...");
			}

			final var actualPort = instance.configuration().port();
			System.out.printf("Process %d listening to port %d - Send SIGKILL to shutdown.%n",
					ProcessHandle.current().pid(), actualPort);

			final var mclient = new MastodonClient.Builder(mastodonHost).accessToken(mastodonAccessToken).build();

			final var timer = new Timer(true);
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					try {
						LOGGER.info("Processing events...");
						final Map<URI, Instant> lastRuns = Files.exists(lastRunFile) ? Files.lines(lastRunFile)
								.map(line -> line.split("\\s+", 2))
								.filter(tokens -> tokens.length == 2)
								.collect(Collectors.toMap(
										tokens -> URI.create(tokens[0]),
										tokens -> Instant.parse(tokens[1])
						)) : new HashMap<>(calendarSources.size());
						calendarSources.forEach(calendarSource -> {
							LOGGER.fine(() -> "Requesting iCalendar from '%s'...".formatted(calendarSource.uri()));
							final var lastRun = lastRuns.getOrDefault(calendarSource.uri(), Instant.EPOCH);
							final var startOfPullingCalendar = Instant.now();
							try {
								final var iCalendar = calendarSource.invocation().invoke(Calendar.class);
								lastRuns.put(calendarSource.uri(), startOfPullingCalendar); // in case http request failed, this will stay untouched, so we do not miss recent changes :-)
								iCalendar.getComponents(VEVENT).stream()
										.filter(VEvent.class::isInstance)
										.map(VEvent.class::cast)
										.peek(vevent -> LOGGER.finest(() -> "Received VEVENT: %s".formatted(vevent)))
										.map(Event::of)
										.filter(event -> {
											if (Instant.from(event.version()).isAfter(lastRun))
												return true;

											LOGGER.finer(() -> "Ignoring event because it was not changed since last run: '%s'.".formatted(event.uid()));
											return false;
										})
										.filter(event -> {
											if (event.status() == null || event.status().equals("CONFIRMED"))
												return true;

											LOGGER.finer(() -> "Ignoring event because its status is '%s': '%s'".formatted(event.status(), event.uid()));
											return false;
										})
										.filter(event -> {
											if (event.classification() == null || Set.of("PUBLIC", "PRIVATE", "CONFIDENTIAL").contains(event.classification()))
												return true;

											LOGGER.finer(() -> "Ignoring event because classification is '%s': '%s'".formatted(event.classification(), event.uid()));
											return false;
										})
										.filter(event ->
											switch (event.begin()) {
												case ZonedDateTime zonedDatetime when zonedDatetime.toInstant().isAfter(startOfPullingCalendar) -> true;
												case OffsetDateTime offsetDateTime when offsetDateTime.toInstant().isAfter(startOfPullingCalendar) -> true;
												case LocalDateTime localDateTime when localDateTime.atZone(EUROPE_BERLIN).toInstant().isAfter(startOfPullingCalendar) -> true;
												case LocalDate localDate when localDate.atStartOfDay().atZone(EUROPE_BERLIN).toInstant().isAfter(startOfPullingCalendar) -> true;
												case ZonedDateTime zonedDatetime -> {
													LOGGER.finer(() -> "Ignoring event because it begins in the past: '%s'.".formatted(event.uid()));
													yield false;
												}
												case OffsetDateTime offsetDateTime -> {
													LOGGER.finer(() -> "Ignoring event because it begins in the past: '%s'.".formatted(event.uid()));
													yield false;
												}
												case LocalDateTime localDateTime -> {
													LOGGER.finer(() -> "Ignoring event because it begins in the past: '%s'.".formatted(event.uid()));
													yield false;
												}
												case LocalDate localDate -> {
													LOGGER.finer(() -> "Ignoring event because it begins in the past: '%s'.".formatted(event.uid()));
													yield false;
												}
												default -> {
													LOGGER.warning(() -> "Ignoring event because it has an unsupported temporal class '%s': '%s'.".formatted(event.begin().getClass(), event.uid()));
													yield false;
												}
											}
										)
										.peek(event -> LOGGER.finer(() -> "Processing event: '%s'".formatted(event.uid())))
										.forEach(event -> {
									try {
										final var visibility = switch (event.classification()) {
											case "PUBLIC" -> PUBLIC;
											case "PRIVATE" -> PRIVATE;
											case "CONFIDENTIAL" -> DIRECT;
											case null -> PUBLIC; // According to RFC https://www.rfc-editor.org/rfc/rfc5545#section-3.8.1.3: Missing classification means "public".
											default -> PRIVATE; // According to RFC https://www.rfc-editor.org/rfc/rfc5545#section-3.8.1.3: Unknown classification means "private".
										};

										final var link = event.url() != null ? "\n🌍 " + event.url() : "";
										LOGGER.finest(() -> "Link: " + link);

										// Wir basteln uns einen Spoiler mit den wichtigsten Informationen (Titel, Wann, Wo)
										message.setLength(0);
										if (event.summary() != null)
											message.append("📢 ").append(event.summary());
										if (event.begin() != null)
											message.append("\n📅 ").append(GERMAN_TIMESTAMP_FORMATTER.format(event.begin()));
										if (event.location() != null)
											message.append("\n🏠️ ").append(event.location());
										final var splr = message.toString();
										LOGGER.finest(() -> "Spoiler: " + splr);

										// Wir basteln uns eine Message (Langversion, URL)
										message.setLength(0);
										if (event.description() != null) {
											// Da Mastodon zu große Nachrichten nicht annimmt, müssen wir die Langversion nötigenfalls einkürzen (kenntlich gemacht durch angehängte Ellipse)
											final var maxDescriptionLength = MAXIMUM_MASTODON_MESSAGE_LENGTH - splr.length() - link.length();
											final var actualDescriptionLength = event.description().length();
											if (actualDescriptionLength <= maxDescriptionLength || maxDescriptionLength < 0 /* Use full length, as we skip the description completely (see below) */)
												message.append(event.description()); // Passt, daher keine Ellipse
											else
												message.append(event.description().substring(0, maxDescriptionLength - 1)).append('…');
										}
										final var msge = message.toString();
										LOGGER.finest(() -> "Message: " + msge);

										if (msge.length() + link.length() + splr.length() <= MAXIMUM_MASTODON_MESSAGE_LENGTH) {
											mclient.statuses().postStatus(msge + link, null, visibility, null, false, splr /*, null, false*/).execute();
											LOGGER.fine("Posted event (Spoiler, Message and Link)");
										} else if (splr.length() + link.length() <= MAXIMUM_MASTODON_MESSAGE_LENGTH) {
											mclient.statuses().postStatus(splr + link, null, visibility, null, false, null /*, null, false*/).execute();
											LOGGER.finer("Posted event (Spoiler and Link)");
										} else if (splr.length() <= MAXIMUM_MASTODON_MESSAGE_LENGTH) {
											mclient.statuses().postStatus(splr, null, visibility, null, false, null /*, null, false*/).execute();
											LOGGER.finer("Posted event (Spoiler only)");
										} else if (!link.isEmpty() && link.length() <= MAXIMUM_MASTODON_MESSAGE_LENGTH) {
											mclient.statuses().postStatus(link, null, visibility, null, false, null /*, null, false*/).execute();
											LOGGER.finer("Posted event (Link only)");
										} else
											LOGGER.warning(() -> "Ignoring event, as even the shortest feasible variant would still be too long: '%s'".formatted(event.uid()));
									} catch (final BigBoneRequestException e) {
										e.printStackTrace();
									}
								});
							} catch (final ProcessingException | WebApplicationException e) {
								LOGGER.severe(() -> "Failed to download calendar from '%s': '%s'".formatted(calendarSource.uri(), e.getMessage()));
							}
						});

						final var lastRunContent = lastRuns.entrySet().stream()
								.map(entry -> "" + entry.getKey() + ' ' + entry.getValue())
								.collect(Collectors.joining(System.lineSeparator()));
						Files.writeString(lastRunFile, lastRunContent, CREATE, TRUNCATE_EXISTING, WRITE);
						LOGGER.info(() -> "Waiting %d ms for next polling interval...".formatted(pollingInterval));
					} catch (final IOException e) {
						e.printStackTrace();
					}
				}
			}, 5, pollingInterval);
		});

		Thread.currentThread().join();
	}

	private static class CindyApplication extends Application {
		@Override
		public Set<Class<?>> getClasses() {
			return Set.of(CindyResource.class);
		}
	}

	private static enum HEALTH {
		SUCCESS, UNHEALTHY, RESERVED
	}

	private static HEALTH checkHealth(final int port, final boolean ignoreErrors) {
		try {
			final var uri = UriBuilder.newInstance().scheme("http").host("localhost").port(port)
					.path(CindyResource.class).path(CindyResource.class, "health");
			System.out.println("HEALTHCHECK " + uri);
			if (!EnumSet.of(SUCCESSFUL, REDIRECTION)
					.contains(ClientBuilder.newClient().target(uri).request().get().getStatusInfo().getFamily()))
				throw new WebApplicationException();

			System.out.println("HEALTHY");
			return HEALTH.SUCCESS;
		} catch (final Throwable t) {
			System.out.printf("Unhealthy state detected: %s%n", t.getMessage());
			return ignoreErrors ? HEALTH.SUCCESS : HEALTH.UNHEALTHY;
		}
	}

	private static final record Event(
			String uid,
			Temporal version,
			String summary,
			String description,
			Temporal begin,
			String location,
			String url,
			String classification,
			String status) {
		private static Event of(final VEvent vevent) {
			return new Event(
					textOrNull(vevent, UID),
					Optional.ofNullable(temporalOrNull(vevent, LAST_MODIFIED))
							.orElse(Optional.ofNullable(temporalOrNull(vevent, CREATED))
							.orElse(temporalOrNull(vevent, DTSTAMP))),
					textOrNull(vevent, SUMMARY),
					textOrNull(vevent, DESCRIPTION),
					temporalOrNull(vevent, DTSTART),
					textOrNull(vevent, LOCATION),
					textOrNull(vevent, URL),
					textOrNull(vevent, CLASS),
					textOrNull(vevent, STATUS));
		}

		private static String textOrNull(final Component component, final String propertyName) {
			return component.getProperty(propertyName).map(Property::getValue).orElse(null);
		}

		private static Temporal temporalOrNull(final Component component, final String datePropertyName) {
			return component.getProperty(datePropertyName).filter(DateProperty.class::isInstance).map(DateProperty.class::cast).map(DateProperty::getDate).orElse(null);
		}
	};

	private static record CalendarSource(URI uri, Invocation invocation) {
		CalendarSource(URI uri, Invocation invocation) {
			this.uri = requireNonNull(uri);
			this.invocation = requireNonNull(invocation);
		}
	};
}
