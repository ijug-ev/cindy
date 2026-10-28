import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;

/**
 * Logs unexpected exceptions with their full stack trace and responds with an empty "500 Internal Server Error".
 */
public final class RuntimeExceptionMapper implements ExceptionMapper<RuntimeException> {
	private static final Logger LOGGER = Logger.getLogger(RuntimeExceptionMapper.class.getName());

	@Override
	public Response toResponse(final RuntimeException e) {
		if (e instanceof final WebApplicationException wae) {
			return wae.getResponse();
		}
		LOGGER.log(Level.SEVERE, "Request failed", e);
		return Response.serverError().build();
	}
}
