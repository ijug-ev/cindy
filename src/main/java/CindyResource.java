import static net.fortuna.ical4j.model.Component.VEVENT;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;

/**
 * @author Markus KARG (markus@headcrashing.eu)
 */
@Path("cindy")
public class CindyResource {
  private static final Logger LOGGER = Logger.getLogger(CindyResource.class.getName());
  private static final ZoneId EVENT_ZONE = ZoneId.of("Europe/Berlin");
  private static final DateTimeFormatter START_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)
      .withZone(EVENT_ZONE);
  private static final String CSV_HEADER = "ICS source,Start (Europe/Berlin),Title,Description,Location";

  private final List<CindyBootstrap.CalendarSource> calendarSources;

  public CindyResource() {
    this(List.of());
  }

  CindyResource(final List<CindyBootstrap.CalendarSource> calendarSources) {
    this.calendarSources = List.copyOf(calendarSources);
  }

  @GET
  @Path("health")
  public void health() {
    // Implies 204 No Content, i. e. HEALTHY state
  }

  @GET
  @Path("events.csv")
  @Produces("text/csv; charset=utf-8")
  public Response eventsCsv() {
    final var now = ZonedDateTime.now(EVENT_ZONE);
    //final var end = now.plusMonths(1);
    final var events = new ArrayList<CsvEvent>();

    for (final var source : calendarSources) {
      try {
        final Calendar calendar = source.invocation().invoke(Calendar.class);
        final var sourceName = calendar.getProperty("X-WR-CALNAME").map(property -> property.getValue()).orElse(null);
        final var name = sourceName == null || sourceName.isBlank() ? sourceLabel(source.uri()) : sourceName;
        calendar.getComponents(VEVENT).stream()
            .filter(VEvent.class::isInstance)
            .map(VEvent.class::cast)
            .map(CindyBootstrap.Event::of)
            .filter(event -> event.status() == null || event.status().equals("CONFIRMED"))
            .filter(event -> event.classification() == null || event.classification().equals("PUBLIC"))
            .map(event -> new CsvEvent(name, event, startDateTime(event.begin())))
            .filter(event -> event.start() != null && !event.start().isBefore(now)) //&& event.start().isBefore(end))
            .forEach(events::add);
      } catch (final ProcessingException | WebApplicationException e) {
        LOGGER.warning(() -> "Failed to download calendar from '%s': '%s'".formatted(source.uri(), e.getMessage()));
      }
    }

    final var csv = new StringBuilder(CSV_HEADER).append("\r\n");
    events.stream().sorted(Comparator.comparing(CsvEvent::start)).forEach(event -> csv
        .append(csvCell(event.sourceName())).append(',')
        .append(csvCell(START_FORMATTER.format(event.start()))).append(',')
        .append(csvCell(event.event().summary())).append(',')
        .append(csvCell(event.event().description())).append(',')
        .append(csvCell(event.event().location())).append("\r\n"));

    return Response.ok(csv.toString(), "text/csv; charset=utf-8")
        .header("Content-Disposition", "attachment; filename=\"events.csv\"")
        .build();
  }

  private static ZonedDateTime startDateTime(final Temporal begin) {
    return switch (begin) {
      case null -> null;
      case ZonedDateTime dateTime -> dateTime.withZoneSameInstant(EVENT_ZONE);
      case OffsetDateTime dateTime -> dateTime.atZoneSameInstant(EVENT_ZONE);
      case LocalDateTime dateTime -> dateTime.atZone(EVENT_ZONE);
      case LocalDate date -> date.atStartOfDay(EVENT_ZONE);
      default -> null;
    };
  }

  private static String csvCell(final String value) {
    return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
  }

  private static String sourceLabel(final URI uri) {
    final var label = new StringBuilder();
    if (uri.getHost() != null) {
      if (uri.getScheme() != null)
        label.append(uri.getScheme()).append("://");
      label.append(uri.getHost());
    } else if (uri.getScheme() != null) {
      label.append(uri.getScheme());
    }
    if (uri.getPort() >= 0)
      label.append(':').append(uri.getPort());
    return label.isEmpty() ? "calendar" : label.toString();
  }

  private record CsvEvent(String sourceName, CindyBootstrap.Event event, ZonedDateTime start) {
  }

}
