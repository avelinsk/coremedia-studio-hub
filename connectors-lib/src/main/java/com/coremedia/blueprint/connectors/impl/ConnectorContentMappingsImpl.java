package com.coremedia.blueprint.connectors.impl;

import com.coremedia.blueprint.connectors.api.ConnectorContentMappings;
import com.coremedia.cap.content.Content;
import com.coremedia.cap.struct.Struct;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Stores the content mappings and creates ContentCreators out of it
 */
public class ConnectorContentMappingsImpl implements ConnectorContentMappings {
  private static final String SETTINGS = "settings";
  private static final String DEFAULT_MAPPING = "default";
  private Map<String, Object> properties;

  ConnectorContentMappingsImpl(Content content) {
    Struct settings = content.getStruct(SETTINGS);
    properties = settings.getProperties();
  }

  @Nonnull
  @Override
  public Map<String, Object> getProperties() {
    return properties;
  }

  @Nonnull
  @Override
  public String get(String type) {
    if(properties.containsKey(type)) {
      return (String) properties.get(type);
    }
    if(!properties.containsKey(DEFAULT_MAPPING)) {
      throw new UnsupportedOperationException("The connector content mapping must contain a 'default' mapping.");
    }
    return (String) properties.get(DEFAULT_MAPPING);
  }
}
