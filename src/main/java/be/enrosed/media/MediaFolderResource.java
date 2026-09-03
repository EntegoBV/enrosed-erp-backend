package be.enrosed.media;

import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** The folder tree of the library. */
@Path("/api/media-folders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class MediaFolderResource {
    private final MediaService media;

    public MediaFolderResource(MediaService media) {
        this.media = media;
    }

    @GET
    public List<MediaDtos.Folder> list() {
        return media.folders();
    }

    @POST
    public Response create(MediaDtos.FolderRequest request) {
        return Response.status(Response.Status.CREATED).entity(media.createFolder(request)).build();
    }

    @PUT
    @Path("/{id}")
    public MediaDtos.Folder update(@PathParam("id") long id, MediaDtos.FolderRequest request) {
        return media.updateFolder(id, request);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        media.deleteFolder(id);
        return Response.noContent().build();
    }
}
