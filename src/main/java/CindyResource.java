import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * @author Markus KARG (markus@headcrashing.eu)
 */
@Path("cindy")
public class CindyResource {

  @GET
  @Path("health")
  public void health() {
    // Implies 204 No Content, i. e. HEALTHY state
  }

}
