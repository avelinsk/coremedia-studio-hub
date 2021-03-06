package com.coremedia.blueprint.connectors.filesystem;

import com.coremedia.blueprint.connectors.api.ConnectorCategory;
import com.coremedia.blueprint.connectors.api.ConnectorContext;
import com.coremedia.blueprint.connectors.api.ConnectorEntity;
import com.coremedia.blueprint.connectors.api.ConnectorException;
import com.coremedia.blueprint.connectors.api.ConnectorId;
import com.coremedia.blueprint.connectors.api.ConnectorItem;
import com.coremedia.blueprint.connectors.filesystems.FileBasedConnectorService;
import com.coremedia.blueprint.connectors.api.search.ConnectorSearchResult;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.coremedia.blueprint.connectors.impl.ConnectorPropertyNames.DISPLAY_NAME;
import static com.coremedia.blueprint.connectors.impl.ConnectorPropertyNames.FOLDER;

public class FileSystemConnectorServiceImpl extends FileBasedConnectorService<File> {
  private static final Logger LOGGER = LoggerFactory.getLogger(FileSystemConnectorServiceImpl.class);

  private FileSystemConnectorCategory rootCategory;

  @Override
  public boolean init(@Nonnull ConnectorContext context) {
    super.init(context);
    String rootPath = context.getProperty(FOLDER);
    File file = new File(rootPath);
    if(!file.exists()) {
      LOGGER.warn("File Connector folder '" + file.getAbsolutePath() + " does not exist, connector will be ignored.");
    }
    return file.exists();
  }

  @Override
  public Boolean refresh(@Nonnull ConnectorCategory category) {
    if(category.getConnectorId().isRootId()) {
      rootCategory = null;
      rootCategory = (FileSystemConnectorCategory) getRootCategory();
    }
    return super.refresh(category);
  }

  @Nonnull
  @Override
  public ConnectorCategory getRootCategory() throws ConnectorException {
    if (rootCategory == null) {
      String rootPath = context.getProperty(FOLDER);
      String displayName = context.getProperty(DISPLAY_NAME);

      if (StringUtils.isEmpty(displayName)) {
        displayName = new File(rootPath).getName();
      }
      if (StringUtils.isEmpty(displayName)) {
        displayName = rootPath;
      }

      if(rootPath == null) {
        throw new ConnectorException("No root folder set for file connector, " +
                "ensure that the 'folder' property is set in the connection settings.");
      }

      File rootFolder = new File(rootPath);
      if(!rootFolder.exists()) {
        throw new ConnectorException("Folder '" + rootPath + "' for file connector does exists.");
      }

      ConnectorId id = ConnectorId.createRootId(context.getConnectionId());
      rootCategory = new FileSystemConnectorCategory(null, context, id, rootFolder);
      rootCategory.setName(displayName);

      List<ConnectorCategory> subCategories = getSubCategories(rootCategory);
      rootCategory.setSubCategories(subCategories);

      List<ConnectorItem> items = getItems(rootCategory);
      rootCategory.setItems(items);
    }
    return rootCategory;
  }

  @Nullable
  @Override
  public ConnectorItem getItem(@Nonnull ConnectorId itemId) throws ConnectorException {
    ConnectorId parentFolderId = getFolderId(itemId);
    File file = getCachedFileOrFolderEntity(itemId);
    return new FileSystemConnectorItem(getCategory(parentFolderId), context, itemId, file);
  }

  @Nullable
  @Override
  public ConnectorCategory getCategory(@Nonnull ConnectorId categoryId) throws ConnectorException {
    ConnectorCategory parentCategory = getParentCategory(categoryId);
    if (parentCategory == null) {
      return getRootCategory();
    }

    File file = getCachedFileOrFolderEntity(categoryId);
    FileSystemConnectorCategory subCategory = new FileSystemConnectorCategory(parentCategory, context, categoryId, file);
    subCategory.setItems(getItems(subCategory));
    subCategory.setSubCategories(getSubCategories(subCategory));
    return subCategory;
  }

  @Nonnull
  @Override
  public ConnectorSearchResult<ConnectorEntity> search(ConnectorCategory category, String query, String searchType, Map<String, String> params) {
    List<ConnectorEntity> results = new ArrayList<>();

    FileSystemConnectorCategory fileSystemCategory = (FileSystemConnectorCategory) category;
    File folder = fileSystemCategory.getFile();

    if (query.equals("*")) {
      if (searchType == null || searchType.equals(ConnectorCategory.DEFAULT_TYPE)) {
        results.addAll(category.getSubCategories());
      }

      File[] files = folder.listFiles();
      if (files != null) {
        for (File file : files) {
          if (isValid(file)) {
            ConnectorId itemId = ConnectorId.createItemId(context.getConnectionId(), getPath(file));
            ConnectorItem item = new FileSystemConnectorItem(fileSystemCategory, context, itemId, file);
            if (item.isMatchingWithItemType(searchType)) {
              results.add(item);
            }
          }
        }
      }
    }
    else {
      try {
        Files.walk(Paths.get(folder.getAbsolutePath()))
                .filter(Files::isRegularFile)
                .forEach((f) -> {
                  if (f.getFileName().toString().toLowerCase().contains(query.toLowerCase()) && isValid(f.toFile())) {
                    ConnectorId itemId = ConnectorId.createItemId(context.getConnectionId(), getPath(f.toFile()));
                    ConnectorItem item = new FileSystemConnectorItem(fileSystemCategory, context, itemId, f.toFile());
                    if (item.isMatchingWithItemType(searchType)) {
                      results.add(item);
                    }
                  }
                });
      } catch (IOException e) {
        LOGGER.warn("Error executing file system search", e);
      }
    }
    return new ConnectorSearchResult<>(results);
  }

  @Override
  public ConnectorItem upload(ConnectorCategory category, String itemName, InputStream inputStream) {
    String uniqueObjectName = createUniqueFilename(category.getConnectorId(), itemName);
    ConnectorId newItemId = ConnectorId.createItemId(context.getConnectionId(), uniqueObjectName);
    File file = new File(newItemId.getExternalId());

    try {
      BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file));
      IOUtils.copy(inputStream, out);
      inputStream.close();
      out.close();
      return getItem(newItemId);
    } catch (IOException e) {
      LOGGER.error("Failed to created system file " + file.getAbsolutePath() + ": " + e.getMessage(), e);
      throw new ConnectorException(e);
    }
  }

  //---------------------------File System Connector -------------------------------------------------------------------

  public List<File> list(ConnectorId categoryId) {
    String path = categoryId.getExternalId();
    if(categoryId.isRootId()) {
      path = context.getProperty(FOLDER);
    }

    File[] files = new File(path).listFiles((dir, name) -> {
      File f = new File(dir, name);
      return isValid(f);
    });
    if(files == null) {
      return Collections.emptyList();
    }
    return Arrays.asList(files);
  }

  public File getFile(ConnectorId id) {
    String path = id.getExternalId();
    return new File(path);
  }

  public boolean isFile(File metadata) {
    return metadata.isFile();
  }

  public String getName(File metadata) {
    return metadata.getName();
  }

  public String getPath(File metadata) {
    return metadata.getPath().replaceAll("\\\\", "/");
  }

  //----------------------------- Helper -------------------------------------------------------------------------------

  private List<ConnectorCategory> getSubCategories(@Nonnull ConnectorCategory category) throws ConnectorException {
    List<ConnectorCategory> subCategories = new ArrayList<>();

    List<File> subfolders = getSubfolderEntities(category.getConnectorId());
    for (File entry : subfolders) {
      ConnectorId connectorId = ConnectorId.createCategoryId(context.getConnectionId(), getPath(entry));
      FileSystemConnectorCategory subCategory = new FileSystemConnectorCategory(category, context, connectorId, entry);
      subCategory.setItems(getItems(subCategory));
      subCategories.add(subCategory);
    }

    return subCategories;
  }

  private List<ConnectorItem> getItems(@Nonnull ConnectorCategory category) throws ConnectorException {
    List<ConnectorItem> items = new ArrayList<>();

    List<File> fileEntities = getFileEntities(category.getConnectorId());
    for (File entry : fileEntities) {
      ConnectorId itemId = ConnectorId.createItemId(context.getConnectionId(), getPath(entry));
      FileSystemConnectorItem item = new FileSystemConnectorItem(category, context, itemId, entry);
      items.add(item);
    }

    return items;
  }

  private boolean isValid(File file) {
    return !file.isHidden();
  }

}
