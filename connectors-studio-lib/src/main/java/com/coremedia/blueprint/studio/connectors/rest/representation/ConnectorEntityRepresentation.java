package com.coremedia.blueprint.studio.connectors.rest.representation;

import com.coremedia.blueprint.connectors.api.ConnectorCategory;
import com.coremedia.blueprint.connectors.api.ConnectorId;
import com.coremedia.blueprint.studio.connectors.rest.model.ConnectorModel;
import org.codehaus.jackson.map.annotate.JsonSerialize;

import java.net.URI;
import java.util.Date;

public class ConnectorEntityRepresentation {

  private URI deleteUri;

  private ConnectorModel connector;
  private String connectorType;
  private String displayName;
  private String name;
  private ConnectorId connectorId;
  private Date lastModified;
  private ConnectorCategory parent;
  private String managementUrl;
  private boolean deleteable;

  @JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
  public Date getLastModified() {
    return lastModified;
  }

  public void setLastModified(Date lastModified) {
    this.lastModified = lastModified;
  }

  public ConnectorModel getConnector() {
    return connector;
  }

  public void setConnector(ConnectorModel connector) {
    this.connector = connector;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public ConnectorCategory getParent() {
    return parent;
  }

  public void setParent(ConnectorCategory parent) {
    this.parent = parent;
  }

  public String getConnectorType() {
    return connectorType;
  }

  public void setConnectorType(String connectorType) {
    this.connectorType = connectorType;
  }

  public ConnectorId getConnectorId() {
    return connectorId;
  }

  public void setConnectorId(ConnectorId connectorId) {
    this.connectorId = connectorId;
  }

  public String getManagementUrl() {
    return managementUrl;
  }

  public void setManagementUrl(String managementUrl) {
    this.managementUrl = managementUrl;
  }

  public URI getDeleteUri() {
    return deleteUri;
  }

  public void setDeleteUri(URI deleteUri) {
    this.deleteUri = deleteUri;
  }

  public boolean isDeleteable() {
    return deleteable;
  }

  public void setDeleteable(boolean deleteable) {
    this.deleteable = deleteable;
  }
}
