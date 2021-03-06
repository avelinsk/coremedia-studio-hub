package com.coremedia.blueprint.studio.connectors.rest;

import com.coremedia.blueprint.connectors.api.ConnectorCategory;
import com.coremedia.blueprint.connectors.api.ConnectorConnection;
import com.coremedia.blueprint.connectors.api.ConnectorEntity;
import com.coremedia.blueprint.connectors.api.ConnectorId;
import com.coremedia.blueprint.connectors.api.ConnectorItem;
import com.coremedia.blueprint.connectors.api.ConnectorService;
import com.coremedia.blueprint.studio.connectors.rest.representation.ConnectorCategoryRepresentation;
import com.coremedia.blueprint.studio.connectors.rest.representation.ConnectorChildRepresentation;
import com.coremedia.rest.linking.LocationHeaderResourceFilter;
import com.sun.jersey.core.header.FormDataContentDisposition;
import com.sun.jersey.multipart.FormDataBodyPart;
import com.sun.jersey.multipart.FormDataParam;
import com.sun.jersey.spi.container.ResourceFilters;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A resource to receive categories.
 */
@Produces(MediaType.APPLICATION_JSON)
@Path("connector/category/{id:[^/]+}")
public class ConnectorCategoryResource extends ConnectorEntityResource<ConnectorCategory> {

  @Override
  protected ConnectorCategory doGetEntity() {
    ConnectorId id = ConnectorId.toId(getDecodedId());
    ConnectorConnection connection = getConnection(id);
    if (connection != null) {

      if (id.isRootId()) {
        return connection.getConnectorService().getRootCategory();
      }
      return connection.getConnectorService().getCategory(id);
    }
    return null;
  }


  @GET
  @Path("refresh")
  @Produces(MediaType.APPLICATION_JSON)
  public Boolean refresh() {
    ConnectorCategory category = getEntity();
    ConnectorConnection connection = getConnection(category.getConnectorId());
    ConnectorService service = connection.getConnectorService();
    return service.refresh(category);
  }

  @POST
  @Path("upload")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @ResourceFilters(value = {LocationHeaderResourceFilter.class})
  public ConnectorItem handleBlobUpload(@HeaderParam("site") String siteId,
                                                          @FormDataParam("name") String name,
                                                          @FormDataParam("file") InputStream inputStream,
                                                          @FormDataParam("file") FormDataContentDisposition fileDetail,
                                                          @FormDataParam("file") FormDataBodyPart fileBodyPart) {
    ConnectorCategory category = getEntity();
    String fileName = fileDetail.getFileName();
    String itemName = (name == null) ? fileName : name;

    ConnectorConnection connection = getConnection(category.getConnectorId());
    ConnectorService service = connection.getConnectorService();
    return service.upload(category, itemName, inputStream);
  }


  @Override
  protected ConnectorCategoryRepresentation getRepresentation() throws URISyntaxException {
    ConnectorCategoryRepresentation representation = new ConnectorCategoryRepresentation();
    fillRepresentation(representation);
    fillCategoryRepresentation(representation);
    return representation;
  }

  private void fillCategoryRepresentation(ConnectorCategoryRepresentation representation) throws URISyntaxException {
    ConnectorCategory entity = getEntity();

    representation.setRefreshUri(new URI("connector/category/" + entity.getConnectorId().toUri() + "/refresh"));
    representation.setDeleteUri(new URI("connector/category/" + entity.getConnectorId().toUri() + "/delete"));
    representation.setDeleteUri(new URI("connector/category/" + entity.getConnectorId().toUri() + "/delete"));
    representation.setUploadUri(new URI("connector/category/" + entity.getConnectorId().toUri() + "/upload"));
    representation.setWriteable(entity.isWriteable());
    representation.setType(entity.getType());

    List<ConnectorCategory> subCategories = entity.getSubCategories();
    representation.setSubCategories(subCategories);
    List<ConnectorItem> items = entity.getItems();
    representation.setItems(items);
    List<ConnectorEntity> children = new ArrayList<>();
    children.addAll(subCategories);
    children.addAll(items);
    representation.setChildren(children);

    Map<String, ConnectorChildRepresentation> childrenByName = new LinkedHashMap<>();
    for (ConnectorEntity child : children) {
      if (child == null) {
        continue;
      }
      ConnectorChildRepresentation childRepresentation = new ConnectorChildRepresentation();
      childRepresentation.setChild(child);
      if (child instanceof ConnectorCategory) {
        childRepresentation.setDisplayName(child.getDisplayName());
      }
      else {
        childRepresentation.setDisplayName(child.getName());
      }

      childrenByName.put(child.getName(), childRepresentation);
    }

    representation.setChildrenByName(childrenByName);
  }

  @Override
  public void setEntity(ConnectorCategory category) {
    super.setEntity(category);
  }
}
