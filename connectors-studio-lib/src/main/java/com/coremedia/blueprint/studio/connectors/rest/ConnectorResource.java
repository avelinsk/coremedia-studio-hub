package com.coremedia.blueprint.studio.connectors.rest;

import com.coremedia.blueprint.connectors.impl.Connectors;
import com.coremedia.blueprint.connectors.api.ConnectorConnection;
import com.coremedia.blueprint.studio.connectors.rest.model.ConnectorModel;
import com.coremedia.blueprint.studio.connectors.rest.representation.ConnectorRepresentation;
import com.coremedia.rest.linking.EntityResource;
import org.springframework.beans.factory.annotation.Required;

import javax.annotation.Nullable;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;

import static com.coremedia.blueprint.studio.connectors.rest.ConnectorResource.CONNECTOR_TYPE;
import static com.coremedia.blueprint.studio.connectors.rest.ConnectorResource.SITE_ID;

/**
 * A resource to retrieve the basic information of a connector.
 */
@Produces(MediaType.APPLICATION_JSON)
@Path("connector/connector/{"+ CONNECTOR_TYPE +":[^/]+}/{" + SITE_ID + ":[^/]+}")
public class ConnectorResource implements EntityResource<ConnectorModel> {
  static final String CONNECTOR_TYPE = "connectorType";
  static final String SITE_ID = "siteId";

  private String connectorType;
  private String siteId;
  private Connectors connector;

  private static final String siteDefault = "all";

  @GET
  public ConnectorRepresentation getRepresentation() {
    ConnectorRepresentation connectorRepresentation = new ConnectorRepresentation();
    ConnectorModel entity = getEntity();
    connectorRepresentation.setName(entity.getName());
    connectorRepresentation.setConnectorType(entity.getConnectorType());
    connectorRepresentation.setConnections(entity.getConnections());
    connectorRepresentation.setContentMappings(entity.getContentMappings());
    connectorRepresentation.setItemTypes(entity.getItemTypes());
    return connectorRepresentation;
  }

  @Override
  public ConnectorModel getEntity() {
    List<ConnectorConnection> connectionsByType = connector.getConnectionsByType(getSiteId(), connectorType);
    ConnectorModel connectorModel = new ConnectorModel(connectorType, connectionsByType);
    connectorModel.setSiteId(siteId);
    return connectorModel;
  }

  @Override
  public void setEntity(ConnectorModel connectorModel) {
    connectorType = connectorModel.getConnectorType();
    siteId = connectorModel.getSiteId();
  }

  @PathParam(CONNECTOR_TYPE)
  public void setConnectorType(@Nullable String connectorType) {
    if (connectorType != null) {
      try {
        this.connectorType = URLDecoder.decode(connectorType, "UTF-8");
        return;
      } catch (UnsupportedEncodingException e) { //NOSONAR - exception ignored on purpose
        //ignore
      }
    }
    this.connectorType = connectorType;
  }

  public String getConnectorType() {
    return connectorType;
  }

  @PathParam(SITE_ID)
  public void setSiteId(@Nullable String siteId) {
    if (siteId != null) {
      try {
        this.siteId = URLDecoder.decode(siteId, "UTF-8");
        return;
      } catch (UnsupportedEncodingException e) { //NOSONAR - exception ignored on purpose
        //ignore
      }
    }
    this.siteId = siteId;
  }

  public String getSiteId() {
    if(siteId != null) {
      return siteId;
    }
    return siteDefault;
  }

  @Required
  public void setConnector(Connectors connector) {
    this.connector = connector;
  }
}
