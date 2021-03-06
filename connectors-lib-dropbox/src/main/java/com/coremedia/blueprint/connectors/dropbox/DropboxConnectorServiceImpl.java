package com.coremedia.blueprint.connectors.dropbox;

import com.coremedia.blueprint.connectors.api.ConnectorCategory;
import com.coremedia.blueprint.connectors.api.ConnectorContext;
import com.coremedia.blueprint.connectors.api.ConnectorEntity;
import com.coremedia.blueprint.connectors.api.ConnectorException;
import com.coremedia.blueprint.connectors.api.ConnectorId;
import com.coremedia.blueprint.connectors.api.ConnectorItem;
import com.coremedia.blueprint.connectors.api.invalidation.InvalidationResult;
import com.coremedia.blueprint.connectors.api.search.ConnectorSearchResult;
import com.coremedia.blueprint.connectors.filesystems.FileBasedConnectorService;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.ListFolderBuilder;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.files.SearchMatch;
import com.dropbox.core.v2.files.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.coremedia.blueprint.connectors.impl.ConnectorPropertyNames.ACCESS_TOKEN;
import static com.coremedia.blueprint.connectors.impl.ConnectorPropertyNames.APP_NAME;
import static com.coremedia.blueprint.connectors.impl.ConnectorPropertyNames.DISPLAY_NAME;

public class DropboxConnectorServiceImpl extends FileBasedConnectorService<Metadata> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DropboxConnectorServiceImpl.class);

  private DropboxConnectorCategory rootCategory;
  private DbxClientV2 client;

  private List<String> latestEntries = new ArrayList<>();

  @Override
  public boolean init(@Nonnull ConnectorContext context) {
    super.init(context);

    String accessToken = context.getProperty(ACCESS_TOKEN);
    String displayName = context.getProperty(DISPLAY_NAME);

    if (accessToken == null || accessToken.trim().length() == 0) {
      throw new ConnectorException("No accessToken configured for Dropbox connection " + context.getConnectionId());
    }

    try {
      DbxRequestConfig config = new DbxRequestConfig(displayName);
      client = new DbxClientV2(config, accessToken);
      latestEntries = getAllEntries();
      return true;
    } catch (Exception e) {
      throw new ConnectorException("Failed to create Dropbox client: " + e.getMessage(), e);
    }
  }

  @Override
  public Boolean refresh(@Nonnull ConnectorCategory category) {
    if (category.getConnectorId().isRootId()) {
      rootCategory = null;
      rootCategory = (DropboxConnectorCategory) getRootCategory();
    }
    return super.refresh(category);
  }

  @Override
  public InvalidationResult invalidate() {
    InvalidationResult invalidationResult = new InvalidationResult(context);

    int added = 0;
    int deleted = 0;

    //find new entries
    List<String> refreshedEntries = getAllEntries();
    List<String> dirtyItems = new ArrayList<>();
    for (String entry : refreshedEntries) {
      if(!latestEntries.contains(entry)) {
        dirtyItems.add(entry);
        added++;
      }
    }

    //find deleted entries
    for (String latestEntry : latestEntries) {
      if(!refreshedEntries.contains(latestEntry)) {
        dirtyItems.add(latestEntry);
        deleted++;
      }
    }

    //prepare invalidation result
    if(deleted > 0 || added > 0) {
      for (String dirtyItem : dirtyItems) {
        ConnectorId id = ConnectorId.createItemId(context.getConnectionId(), dirtyItem);
        ConnectorId folderId = getFolderId(id);
        ConnectorCategory category = getCategory(folderId);
        invalidationResult.addEntity(category);
      }

      refresh(getRootCategory());
      invalidationResult.addMessage("dropbox", rootCategory, Arrays.asList(rootCategory.getName(), added, deleted));
      invalidationResult.addEntity(rootCategory);
    }

    latestEntries = refreshedEntries;
    return invalidationResult;
  }

  @Nonnull
  @Override
  public ConnectorCategory getRootCategory() throws ConnectorException {
    if (rootCategory == null) {
      String displayName = context.getProperty(DISPLAY_NAME);

      ConnectorId id = ConnectorId.createRootId(context.getConnectionId());
      rootCategory = new DropboxConnectorCategory(this, null, context, null, id);
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
    Metadata file = getCachedFileOrFolderEntity(itemId);
    if(file == null) {
      LOGGER.warn("Dropbox item not found for connector id " + itemId);
      return null;
    }
    return new DropboxConnectorItem(this, getCategory(parentFolderId), context, file, itemId);
  }

  @Nullable
  @Override
  public ConnectorCategory getCategory(@Nonnull ConnectorId categoryId) throws ConnectorException {
    ConnectorCategory parentCategory = getParentCategory(categoryId);
    if (parentCategory == null) {
      return getRootCategory();
    }

    Metadata metadata = getCachedFileOrFolderEntity(categoryId);
    DropboxConnectorCategory subCategory = new DropboxConnectorCategory(this, parentCategory, context, metadata, categoryId);
    subCategory.setItems(getItems(subCategory));
    subCategory.setSubCategories(getSubCategories(subCategory));
    return subCategory;
  }

  @Nonnull
  @Override
  public ConnectorSearchResult<ConnectorEntity> search(ConnectorCategory category, String query, String searchType, Map<String, String> params) {
    List<ConnectorEntity> results = new ArrayList<>();
    String path = category.getConnectorId().getExternalId();
    if (category.getConnectorId().isRootId()) {
      path = "";
    }

    if (searchType == null && query.equals("*")) {
      results.addAll(getSubCategories(category));
      results.addAll(getItems(category));
    }
    else if (searchType != null && query.equals("*")) {
      List<ConnectorItem> items = getItems(category);
      for (ConnectorItem item : items) {
        if (item.isMatchingWithItemType(searchType) && item.getParent().getConnectorId().equals(category.getConnectorId())) {
          results.add(item);
        }
      }
    }
    else {
      try {
        if (query == null || query.equals("*")) {
          query = "";
        }
        SearchResult search = client.files().search(path, query);
        List<SearchMatch> matches = search.getMatches();
        for (SearchMatch match : matches) {
          Metadata metadata = match.getMetadata();

          if (metadata instanceof FolderMetadata) {
            ConnectorId id = ConnectorId.createCategoryId(context.getConnectionId(), getPath(metadata));
            ConnectorCategory cat = getCategory(id);
            if (searchType == null || searchType.equals(ConnectorCategory.DEFAULT_TYPE)) {
              results.add(cat);
            }
          }
          else {
            ConnectorId id = ConnectorId.createItemId(context.getConnectionId(), getPath(metadata));
            ConnectorItem item = getItem(id);
            if (item.isMatchingWithItemType(searchType) && item.getParent().getConnectorId().equals(category.getConnectorId())) {
              results.add(item);
            }
          }
        }
      } catch (DbxException e) {
        throw new ConnectorException(e);
      }
    }

    return new ConnectorSearchResult<>(results);
  }

  @Override
  public ConnectorItem upload(ConnectorCategory category, String itemName, InputStream inputStream) {
    try {
      String uniqueObjectName = createUniqueFilename(category.getConnectorId(), itemName);
      ConnectorId newItemId = ConnectorId.createItemId(context.getConnectionId(), uniqueObjectName);
      client.files().uploadBuilder(newItemId.getExternalId()).uploadAndFinish(inputStream);
      inputStream.close();
      return getItem(newItemId);
    } catch (Exception e) {
      LOGGER.error("Failed to upload " + itemName + ": " + e.getMessage(), e);
      throw new ConnectorException(e);
    }
  }

  boolean delete(DropboxConnectorItem item) throws ConnectorException {
    try {
      String path = item.getConnectorId().getExternalId();
      client.files().deleteV2(path);
      refresh(item.getParent());
      return true;
    } catch (DbxException e) {
      throw new ConnectorException(e);
    }
  }

  InputStream stream(DropboxConnectorItem item) throws ConnectorException {
    try {
      String path = item.getConnectorId().getExternalId();
      return client.files().download(path).getInputStream();
    } catch (DbxException e) {
      throw new ConnectorException(e);
    }
  }

  String getAppName() {
    return context.getProperty(APP_NAME);
  }

  //---------------------------File System Connector -------------------------------------------------------------------

  public List<Metadata> list(ConnectorId categoryId) {
    String path = categoryId.getExternalId();
    if (categoryId.isRootId()) {
      path = "";
    }
    try {
      ListFolderResult listFolderResult = client.files().listFolder(path);
      return listFolderResult.getEntries();
    } catch (DbxException e) {
      LOGGER.error("Failed to list dropbox file list for path '" + path + "': " + e.getMessage());
      throw new ConnectorException(e);
    }
  }

  public Metadata getFile(ConnectorId id) {
    String path = id.getExternalId();
    try {
      if (id.isRootId()) {
        return null;
      }
      return client.files().getMetadata(path);
    } catch (DbxException e) {
      throw new ConnectorException("Failed to retrieve dropbox item using path '" + path + ": " + e.getMessage(), e);
    }
  }

  public boolean isFile(Metadata metadata) {
    return metadata instanceof FileMetadata;
  }

  public String getName(Metadata metadata) {
    return metadata.getName();
  }

  public String getPath(Metadata metadata) {
    return metadata.getPathDisplay();
  }

  //----------------------------- Helper -------------------------------------------------------------------------------

  private List<String> getAllEntries() {
    List<String> allEntries = new ArrayList<>();
    try {
      ListFolderBuilder listFolderBuilder = client.files().listFolderBuilder("");
      ListFolderResult result = listFolderBuilder.withRecursive(true).start();

      for (Metadata entry : result.getEntries()) {
        if (entry instanceof FileMetadata) {
          allEntries.add(entry.getPathDisplay());
        }
      }
    } catch (DbxException e) {
      LOGGER.error("Failed to recursively read all dropbox entries: " + e.getMessage(), e);
    }
    return allEntries;
  }

  private List<ConnectorCategory> getSubCategories(@Nonnull ConnectorCategory category) throws ConnectorException {
    List<ConnectorCategory> subCategories = new ArrayList<>();

    List<Metadata> subfolders = getSubfolderEntities(category.getConnectorId());
    for (Metadata entry : subfolders) {
      ConnectorId connectorId = ConnectorId.createCategoryId(context.getConnectionId(), getPath(entry));
      DropboxConnectorCategory subCategory = new DropboxConnectorCategory(this, category, context, entry, connectorId);
      subCategory.setItems(getItems(subCategory));
      subCategories.add(subCategory);
    }

    return subCategories;
  }

  private List<ConnectorItem> getItems(@Nonnull ConnectorCategory category) throws ConnectorException {
    List<ConnectorItem> items = new ArrayList<>();

    List<Metadata> fileEntities = getFileEntities(category.getConnectorId());
    for (Metadata entry : fileEntities) {
      ConnectorId itemId = ConnectorId.createItemId(context.getConnectionId(), getPath(entry));
      DropboxConnectorItem item = new DropboxConnectorItem(this, category, context, entry, itemId);
      items.add(item);
    }

    return items;
  }
}
