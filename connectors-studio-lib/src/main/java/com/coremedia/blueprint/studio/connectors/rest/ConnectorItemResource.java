package com.coremedia.blueprint.studio.connectors.rest;

import com.coremedia.blueprint.connectors.api.ConnectorConnection;
import com.coremedia.blueprint.connectors.api.ConnectorContext;
import com.coremedia.blueprint.connectors.api.ConnectorId;
import com.coremedia.blueprint.connectors.api.ConnectorItem;
import com.coremedia.blueprint.connectors.api.ConnectorMetaData;
import com.coremedia.blueprint.connectors.metadataresolver.ConnectorMetaDataResolver;
import com.coremedia.blueprint.connectors.previewconverters.ConnectorPreviewConverter;
import com.coremedia.blueprint.connectors.previewconverters.PreviewConversionResult;
import com.coremedia.blueprint.studio.connectors.rest.representation.ConnectorItemRepresentation;
import com.coremedia.blueprint.studio.connectors.rest.representation.ConnectorPreviewRepresentation;
import com.coremedia.cap.common.TempFileService;
import com.coremedia.cap.content.ContentRepository;
import com.coremedia.mimetype.MimeTypeService;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A resource to receive pictures.
 */
@Produces(MediaType.APPLICATION_JSON)
@Path("connector/item/{id:[^/]+}")
public class ConnectorItemResource extends ConnectorEntityResource<ConnectorItem> {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConnectorItemResource.class);

  private List<ConnectorPreviewConverter> connectorPreviewConverters;
  private List<ConnectorMetaDataResolver> connectorMetaDataResolvers;
  private MimeTypeService mimeTypeService;
  private ContentRepository contentRepository;

  private enum QueryMode {
    STREAM, DOWNLOAD, OPEN
  }

  @Override
  protected ConnectorItem doGetEntity() {
    ConnectorId id = ConnectorId.toId(getDecodedId());
    ConnectorConnection connection = getConnection(id);
    if (connection != null) {
      return connection.getConnectorService().getItem(id);
    }
    return null;
  }

  @GET
  @Path("data")
  public Response data(@QueryParam("mode") String mode,
                       @Context HttpServletResponse response) {
    QueryMode queryMode = QueryMode.valueOf(mode.toUpperCase());
    switch (queryMode) {
      case OPEN: {
        return writeResponse(response, false, false);
      }
      case STREAM: {
        return writeResponse(response, true, true);
      }
      case DOWNLOAD: {
        return writeResponse(response, false, true);
      }
      default: {
        return writeResponse(response, false, true);
      }
    }
  }

  @GET
  @Path("preview")
  @Produces(MediaType.APPLICATION_JSON)
  public ConnectorPreviewRepresentation preview() {
    ConnectorPreviewRepresentation representation = new ConnectorPreviewRepresentation();
    try {
      //check for custom templates
      ConnectorItem item = getEntity();
      if (item == null) {
        return representation;
      }

      //add metadata
      representation.addMetaData(item.getMetaData());

      if (item.getPreviewHtml() != null) {
        representation.setHtml(item.getPreviewHtml());
      }

      formatPreview(representation, item);
    } catch (Exception e) {
      LOGGER.error("Failed to retrieve text preview for " + getEntity() + ": " + e.getMessage(), e);
    }
    return representation;
  }


  /**
   * Creates a stream or download response object for the given asset
   *
   * @param response       the response to write to
   * @param streamResponse true if the request should be a stream
   */
  private Response writeResponse(HttpServletResponse response, boolean streamResponse, boolean setFilename) {
    try {
      ConnectorItem item = getEntity();
      String filename = getFilename(item);
      String mimeType = mimeTypeService.detectMimeType(null, filename, MediaType.APPLICATION_OCTET_STREAM);
      //we can't return json as mime type since jersey would try to deserialize it.
      if (mimeType.equals(MediaType.APPLICATION_JSON)) {
        mimeType = MediaType.TEXT_PLAIN;
      }

      //make sure to encode text as utf8
      if (mimeType.startsWith("text/")) {
        mimeType = mimeType + "; charset=utf-8";
      }

      response.setHeader("Content-Type", mimeType);
      response.setHeader("X-Frame-Options", "SAMEORIGIN");

      //do not set for open in tab
      if (setFilename) {
        response.setHeader("content-disposition", "attachment; filename = " + item.getName());
      }

      if (streamResponse) {
        InputStream is = item.stream();
        if (is != null) {
          StreamingOutput stream = output -> {
            try {
              readAndWrite(is, output);
            } catch (Exception e) {
              LOGGER.warn("Error during streaming: " + e.getMessage());
            }
          };
          return Response.ok(stream).build();
        }
      }
      return Response.ok(item.stream()).type(mimeType).build();
    } catch (IOException e) {
      throw new WebApplicationException(e);
    }
  }

  @Override
  protected ConnectorItemRepresentation getRepresentation() throws URISyntaxException {
    ConnectorItem entity = getEntity();
    ConnectorItemRepresentation representation = new ConnectorItemRepresentation();
    representation.setDownloadable(entity.isDownloadable());
    representation.setOpenInTabUrl(entity.getOpenInTabUrl());
    representation.setDownloadUrl(entity.getDownloadUrl());
    representation.setStreamUrl(entity.getStreamUrl());
    representation.setStatus(entity.getStatus());
    representation.setDeleteUri(new URI("connector/item/" + entity.getConnectorId().toUri() + "/delete"));
    representation.setPreviewUri(new URI("connector/item/" + entity.getConnectorId().toUri() + "/preview"));
    fillRepresentation(representation);
    fillItemRepresentation(entity, representation);
    return representation;
  }

  @Override
  public void setEntity(ConnectorItem picture) {
    super.setEntity(picture);
  }

  //------------------------------------- Helper -----------------------------------------------------------------------

  private void fillItemRepresentation(ConnectorItem entity, ConnectorItemRepresentation representation) {
    representation.setSize(entity.getSize());
    representation.setItemType(entity.getItemType());
  }


  /**
   * Additional preview processing such as preview formatting and additional metadata retrieval
   */
  private void formatPreview(ConnectorPreviewRepresentation representation, ConnectorItem asset) throws IOException {
    List<ConnectorPreviewConverter> applicableConverters = connectorPreviewConverters.stream().filter(entry -> entry.include(asset)).collect(Collectors.toList());
    List<ConnectorMetaDataResolver> applicableMetaDataResolvers = connectorMetaDataResolvers.stream().filter(entry -> entry.include(asset)).collect(Collectors.toList());

    if (!applicableConverters.isEmpty() || !applicableMetaDataResolvers.isEmpty()) {
      //check threshold before creating a temp file
      ConnectorContext context = getContext(asset.getConnectorId());
      int previewThresholdMB = context.getPreviewThresholdMB();
      int thresholdBytes = previewThresholdMB * 1024 * 1024;
      if (previewThresholdMB != -1 && asset.getSize() > 0 && asset.getSize() > thresholdBytes) {
        representation.setHtml(null);
        return;
      }

      InputStream in = asset.stream();
      if (in != null) {
        File assetTempFile = createTempFile(asset, in);

        //convert the preview based on the temp file
        for (ConnectorPreviewConverter converter : applicableConverters) {
          PreviewConversionResult conversionResult = converter.convert(context, asset, assetTempFile);
          if (conversionResult != null && conversionResult.getResult() != null) {
            representation.setHtml(conversionResult.getResult());
            representation.addMetaData(conversionResult::getMetaData);
            break;
          }
        }

        //read metadata based on the temp file
        for (ConnectorMetaDataResolver metaDataResolver : applicableMetaDataResolvers) {
          ConnectorMetaData metaData = metaDataResolver.resolveMetaData(asset, assetTempFile);
          representation.addMetaData(metaData);
        }

        //finally return plain stream
        if (representation.getHtml() == null) {
          representation.setHtml(FileUtils.readFileToString(assetTempFile, Charset.defaultCharset()));
        }
      }
    }
  }

  /**
   * For streaming
   */
  private void readAndWrite(final InputStream is, OutputStream os) throws IOException {
    try {
      byte[] data = new byte[2048];
      int read;
      while ((read = is.read(data)) > 0) {
        os.write(data, 0, read);
      }
      os.flush();
    } catch (IOException e) {
      //may happen when the selection changes during streaming
      is.close();
    }
  }

  private File createTempFile(ConnectorItem asset, InputStream in) throws IOException {
    TempFileService tempFileService = contentRepository.getConnection().getTempFileService();
    ConnectorId id = asset.getConnectorId();
    File assetTempFile = tempFileService.createTempFileFor(id.getConnectionId() + "-" + id.getExternalId(), ".asset");

    FileUtils.copyToFile(in, assetTempFile);
    in.close();
    return assetTempFile;
  }

  /**
   * Helper to determine a filename for the given asset
   */
  private String getFilename(ConnectorItem item) {
    String name = item.getName();
    if (!name.contains(".")) {
      name = item.getConnectorId().getExternalId() + "." + item.getItemType();
    }

    return name;
  }

  //------------------------------------- Spring -----------------------------------------------------------------------

  @Required
  public void setMimeTypeService(MimeTypeService mimeTypeService) {
    this.mimeTypeService = mimeTypeService;
  }

  public void setConnectorPreviewConverters(List<ConnectorPreviewConverter> connectorPreviewConverters) {
    this.connectorPreviewConverters = connectorPreviewConverters;
  }

  @Required
  public ContentRepository getContentRepository() {
    return contentRepository;
  }

  @Required
  public void setContentRepository(ContentRepository contentRepository) {
    this.contentRepository = contentRepository;
  }

  @Required
  public void setConnectorMetaDataResolvers(List<ConnectorMetaDataResolver> connectorMetaDataResolvers) {
    this.connectorMetaDataResolvers = connectorMetaDataResolvers;
  }
}
