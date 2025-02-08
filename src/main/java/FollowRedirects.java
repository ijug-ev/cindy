import static jakarta.ws.rs.core.Response.Status.MOVED_PERMANENTLY;
import static jakarta.ws.rs.core.Response.Status.PERMANENT_REDIRECT;
import static jakarta.ws.rs.core.Response.Status.Family.REDIRECTION;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.Provider;

/**
 * This feature provides automatic following of redirection requests.
 *
 * <p>
 * This feature is disabled by default. It needs to be enabled per request.
 * To enable it, set the request property {@link #REDIRECTIONS_LIMIT} to any
 * arbitrary value.
 *
 * <p>
 * When a server requests a redirection using a {@code 3XX} status code and a
 * {@code Location} header, an identical (sans entity) request is sent to the
 * proposed location.
 *
 * <p>
 * The total number of redirections automatically followed can be limited by
 * setting the request property {@link #REDIRECTIONS_LIMIT} to an integer.
 * When the maximum is exceeded, or when there is no {@code Location} header
 * provided by the server, no automatic following happens, but the server's last
 * response is forwarded
 * to the client.
 *
 * @implNote This implementation does <em>not</em> respect
 *           https://fetch.spec.whatwg.org/#http-redirect-fetch.
 *
 * @author Markus KARG (markus@headcrashing.eu)
 */
@Provider
final class FollowRedirects implements ClientRequestFilter, ClientResponseFilter {
    public static final String REDIRECTIONS_LIMIT = FollowRedirects.class.getName() + ".redirectionsLimit";
    private static final String REDIRECTIONS_COUNT = FollowRedirects.class.getName() + ".redirectCount";
    private static final Logger LOGGER = Logger.getLogger(FollowRedirects.class.getName());
    private static final Set<Status> PERMANENT_REDIRECTION_STATUSCODES = EnumSet.of(MOVED_PERMANENTLY, PERMANENT_REDIRECT);
    private static final Map<URI, URI> PERMANENT_REDIRECTION_LOCATIONS = new ConcurrentHashMap<>();

    @Override
    public void filter(final ClientRequestContext requestContext) throws IOException {
        final var redirectionsLimit = requestContext.getProperty(REDIRECTIONS_LIMIT);
        if (redirectionsLimit == null)
            return;

        if (!requestContext.hasProperty(REDIRECTIONS_COUNT))
            requestContext.setProperty(REDIRECTIONS_COUNT, 0);

        final var target = requestContext.getUri();
        while (PERMANENT_REDIRECTION_LOCATIONS.get(requestContext.getUri()) instanceof URI location) {
            final var redirectsCount = (int) requestContext.getProperty(REDIRECTIONS_COUNT) + 1;
            if (redirectionsLimit instanceof Integer maxRedirects && redirectsCount > maxRedirects) {
                LOGGER.severe(() -> "Ignoring redirect #%d from '%s' to '%s', as limit %d is exceeded."
                        .formatted(redirectsCount, requestContext.getUri(), location, maxRedirects));
                requestContext.abortWith(Response.status(PERMANENT_REDIRECT).location(location).build());
                return;
            }
            requestContext.setUri(location);
        }
        if (!target.equals(requestContext.getUri()))
            LOGGER.finer(() -> "Following permanent redirect from '%s' to '%s'..."
                    .formatted(target, requestContext.getUri()));
    }

    @Override
    public void filter(final ClientRequestContext requestContext, final ClientResponseContext responseContext)
            throws IOException {
        final var redirectionsLimit = requestContext.getProperty(REDIRECTIONS_LIMIT);
        if (redirectionsLimit == null)
            return;

        if (responseContext.getStatusInfo().getFamily() != REDIRECTION) {
            LOGGER.finer(() -> "Response from '%s' was %d, so there is no redirection to auto-follow."
                    .formatted(requestContext.getUri(), responseContext.getStatus()));
            return;
        }

        final var redirectsCount = (int) requestContext.getProperty(REDIRECTIONS_COUNT) + 1;
        final var location = responseContext.getLocation();

        if (location == null) {
            LOGGER.severe(() -> "Ignoring redirect #%d from '%s' to' %s', as 'Location' header is missing."
                    .formatted(redirectsCount, requestContext.getUri()));
            return;
        }

        if (redirectionsLimit instanceof Integer maxRedirects && redirectsCount > maxRedirects) {
            LOGGER.severe(() -> "Ignoring redirect #%d from '%s' to '%s', as limit %d is exceeded."
                    .formatted(redirectsCount, requestContext.getUri(), location, maxRedirects));
            return;
        }

        if (PERMANENT_REDIRECTION_STATUSCODES.contains(responseContext.getStatusInfo().toEnum())) {
            LOGGER.finer(() -> "Storing permanent redirect from '%s' to '%s'..."
                    .formatted(requestContext.getUri(), location));
            PERMANENT_REDIRECTION_LOCATIONS.put(requestContext.getUri(), location);
        }

        LOGGER.fine(() -> "Following redirect #%d from '%s' to '%s'..."
                .formatted(redirectsCount, requestContext.getUri(), location));
        final var response = requestContext.getClient()
                .target(location).request()
                .headers(requestContext.getHeaders())
                .build(requestContext.getMethod())
                .property(REDIRECTIONS_COUNT, redirectsCount)
                .invoke();
        responseContext.setStatusInfo(response.getStatusInfo());
        responseContext.getHeaders().clear();
        responseContext.getHeaders().forEach(responseContext.getHeaders()::addAll);
        responseContext.setEntityStream(response.readEntity(InputStream.class));
    }
}