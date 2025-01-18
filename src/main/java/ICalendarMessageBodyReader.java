import static jakarta.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM_TYPE;
import static jakarta.ws.rs.core.MediaType.CHARSET_PARAMETER;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.Optional;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarParser;
import net.fortuna.ical4j.data.CalendarParserFactory;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;

/**
 * This feature provides the ability to parse {@code text/calendar} and
 * {@code application/octet-stream} as an {@link Calendar} instance.
 * 
 * @author Markus KARG (markus@headcrashing.eu)
 */
@Provider
class ICalendarMessageBodyReader implements MessageBodyReader<Calendar> {

    private static final CalendarParser CALENDAR_PARSER = CalendarParserFactory.getInstance().get();

    private static final MediaType TEXT_CALENDAR_TYPE = new MediaType("text", "calendar");

    @Override
    public final boolean isReadable(final Class<?> type, final Type genericType, final Annotation[] annotations,
            final MediaType mediaType) {
        return Calendar.class.isAssignableFrom(type) && (mediaType.isCompatible(TEXT_CALENDAR_TYPE)
                || mediaType.isCompatible(APPLICATION_OCTET_STREAM_TYPE));
    }

    @Override
    public final Calendar readFrom(final Class<Calendar> type, final Type genericType, final Annotation[] annotations,
            final MediaType mediaType, final MultivaluedMap<String, String> httpHeaders, final InputStream entityStream)
            throws IOException, WebApplicationException {
        try {
            final var charset = Optional.ofNullable(mediaType.getParameters().get(CHARSET_PARAMETER))
                    .map(Charset::forName).orElse(UTF_8);
            return new CalendarBuilder(CALENDAR_PARSER).build(new InputStreamReader(entityStream, charset));
        } catch (final ParserException e) {
            throw new IOException(e);
        }
    }

}
