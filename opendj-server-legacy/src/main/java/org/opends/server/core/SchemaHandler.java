/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2014-2016 ForgeRock AS.
 */
package org.opends.server.core;

import static org.opends.messages.ConfigMessages.ERR_CONFIG_SCHEMA_DIR_NOT_DIRECTORY;
import static org.opends.messages.ConfigMessages.ERR_CONFIG_SCHEMA_NO_SCHEMA_DIR;
import static org.opends.messages.SchemaMessages.NOTE_SCHEMA_IMPORT_FAILED;
import static org.opends.server.util.SchemaUtils.getElementSchemaFile;
import static org.forgerock.util.Utils.closeSilently;
import static org.opends.messages.ConfigMessages.*;
import static org.opends.messages.SchemaMessages.ERR_SCHEMA_HAS_WARNINGS;
import static org.opends.server.util.ServerConstants.SCHEMA_PROPERTY_FILENAME;
import static org.opends.server.util.StaticUtils.getExceptionMessage;
import static org.opends.server.util.StaticUtils.stackTraceToSingleLineString;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.opends.server.api.AlertGenerator;
import org.opends.server.replication.plugin.HistoricalCsnOrderingMatchingRuleImpl;
import org.opends.server.schema.AciSyntax;
import org.opends.server.schema.AuthPasswordEqualityMatchingRule;
import org.opends.server.schema.SubtreeSpecificationSyntax;
import org.opends.server.schema.UserPasswordEqualityMatchingRule;
import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.opendj.adapter.server3x.Converters;
import org.forgerock.opendj.config.ClassPropertyDefinition;
import org.forgerock.opendj.config.server.ConfigException;
import org.forgerock.opendj.ldap.AttributeDescription;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.Entry;
import org.forgerock.opendj.ldap.ModificationType;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.ldap.schema.AttributeType;
import org.forgerock.opendj.ldap.schema.ConflictingSchemaElementException;
import org.forgerock.opendj.ldap.schema.CoreSchema;
import org.forgerock.opendj.ldap.schema.DITContentRule;
import org.forgerock.opendj.ldap.schema.DITStructureRule;
import org.forgerock.opendj.ldap.schema.MatchingRule;
import org.forgerock.opendj.ldap.schema.MatchingRuleUse;
import org.forgerock.opendj.ldap.schema.NameForm;
import org.forgerock.opendj.ldap.schema.ObjectClass;
import org.forgerock.opendj.ldap.schema.Schema;
import org.forgerock.opendj.ldap.schema.SchemaBuilder;
import org.forgerock.opendj.ldap.schema.SchemaValidationPolicy;
import org.forgerock.opendj.ldap.schema.Syntax;
import org.forgerock.opendj.ldap.schema.AttributeType.Builder;
import org.forgerock.opendj.ldap.schema.SchemaBuilder.SchemaBuilderHook;
import org.forgerock.opendj.ldif.EntryReader;
import org.forgerock.opendj.ldif.LDIFEntryReader;
import org.forgerock.opendj.server.config.meta.SchemaProviderCfgDefn;
import org.forgerock.opendj.server.config.server.RootCfg;
import org.forgerock.opendj.server.config.server.SchemaProviderCfg;
import org.forgerock.util.Option;
import org.forgerock.util.Utils;
import org.opends.server.schema.SchemaProvider;
import org.opends.server.types.Attribute;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.InitializationException;
import org.opends.server.types.Modification;
import org.opends.server.types.SchemaWriter;
import org.opends.server.util.SchemaUtils;
import org.opends.server.util.StaticUtils;

import com.forgerock.opendj.util.OperatingSystem;

/**
 * Responsible for access to the server's schema.
 * <p>
 * The schema handler initializes the schema in four steps :
 * <ul>
 *   <li>Start from the core schema.</li>
 *   <li>Add server specific syntaxes and matching rules</li>
 *   <li>Load schema elements from the schema providers defined in configuration.</li>
 *   <li>Load all schema files located in the schema directory.</li>
 * </ul>
 * <p>
 * The schema handler provides read and write access to the schema.
 * <p>
 * As schema is immutable, there is no risk to alter it outside this handler.
 * However, no long-lived reference should be kept on the schema because further schema
 * updates would be missed. It is advised to retrieve the server's schema from the handler
 * for any operation.
 * <p>
 * The server's schema can be updated using one of the {@code updateSchema()} method.
 */
public final class SchemaHandler
{
  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  private static final String CORE_SCHEMA_PROVIDER_NAME = "Core Schema";
  private static final String CORE_SCHEMA_FILE = "00-core.ldif";
  private static final String RFC_3112_SCHEMA_FILE = "03-rfc3112.ldif";
  private static final String CONFIG_SCHEMA_ELEMENTS_FILE = "02-config.ldif";
  private static final String CORE_SCHEMA_ELEMENTS_FILE = "00-core.ldif";

  private static final AttributeType attributeTypesType = CoreSchema.getAttributeTypesAttributeType();
  private static final AttributeType objectClassesType = CoreSchema.getObjectClassesAttributeType();
  private static final AttributeType ditStructureRulesType = CoreSchema.getDITStructureRulesAttributeType();
  private static final AttributeType ditContentRulesType = CoreSchema.getDITContentRulesAttributeType();
  private static final AttributeType ldapSyntaxesType = CoreSchema.getLDAPSyntaxesAttributeType();
  private static final AttributeType matchingRuleUsesType = CoreSchema.getMatchingRuleUseAttributeType();
  private static final AttributeType nameFormsType = CoreSchema.getNameFormsAttributeType();

  private ServerContext serverContext;

  /**
   * The schema.
   * <p>
   * @GuardedBy("exclusiveLock")
   */
  private volatile Schema schema;

  /** Writer which persists schema to disk. */
  private SchemaWriter schemaWriter;

  /**
   * A set of extra attributes that are not used directly by the schema but may
   * be used by other component to store information in the schema.
   * <p>
   * Example: replication uses this to store its state and GenerationID.
   */
  private Map<String, Attribute> extraAttributes = new HashMap<>();

  /** Guards updates to the schema. */
  private final Lock exclusiveLock = new ReentrantLock();

  /** The oldest modification time for any schema configuration file, as a byte string. */
  private long oldestModificationTime;

  /** The youngest modification time for any schema configuration file. */
  private long youngestModificationTime;


  /**
   * Creates a new instance.
   */
  public SchemaHandler()
  {
    oldestModificationTime = System.currentTimeMillis();
    youngestModificationTime = oldestModificationTime;

    // use a default schema
    schema = Schema.getCoreSchema();
  }

  /**
   * Returns the youngest modification time for schema files.
   *
   * @return the youngest modification time for any schema configuration file
   */
  public long getYoungestModificationTime()
  {
    return youngestModificationTime;
  }

  /**
   * Returns the oldest modification time for schema files.
   *
   * @return the oldest modification time for any schema configuration file
   */
  public long getOldestModificationTime()
  {
    return oldestModificationTime;
  }

  /**
   * Initializes this schema handler.
   *
   * @param serverContext
   *          The server context.
   * @throws ConfigException
   *           If a configuration problem arises in the process of performing
   *           the initialization.
   * @throws InitializationException
   *           If a problem that is not configuration-related occurs during
   *           initialization.
   */
  public void initialize(final ServerContext serverContext) throws InitializationException, ConfigException
  {
    this.serverContext = serverContext;
    this.schemaWriter = new SchemaWriter(serverContext);

    exclusiveLock.lock();
    try
    {
      // Start from the core schema
      final SchemaBuilder schemaBuilder = new SchemaBuilder(Schema.getCoreSchema());

      loadSchemaFromProviders(serverContext.getRootConfig(), schemaBuilder);

      try
      {
        // Add server specific syntaxes not provided by the SDK
        AciSyntax.addAciSyntax(schemaBuilder);
        SubtreeSpecificationSyntax.addSubtreeSpecificationSyntax(schemaBuilder);

        // Add server specific matching rules not provided by the SDK
        HistoricalCsnOrderingMatchingRuleImpl.addHistoricalCsnOrderingMatchingRule(schemaBuilder);
        AuthPasswordEqualityMatchingRule.addAuthPasswordEqualityMatchingRule(schemaBuilder);
        UserPasswordEqualityMatchingRule.addUserPasswordEqualityMatchingRule(schemaBuilder);

      }
      catch (ConflictingSchemaElementException e)
      {
        throw new ConfigException(e.getMessageObject(), e);
      }

      completeSchemaFromFiles(schemaBuilder);

      try
      {
        switchSchema(schemaBuilder.toSchema());
      }
      catch (DirectoryException e)
      {
        throw new ConfigException(e.getMessageObject(), e);
      }
    }
    finally
    {
      exclusiveLock.unlock();
    }
  }

  /**
   * Returns the schema.
   *
   * @return the schema
   */
  public Schema getSchema()
  {
    return schema;
  }

  /**
   * Retrieves the path to the directory containing the server schema files.
   *
   * @return The path to the directory containing the server schema files.
   * @throws InitializationException
   *            If the directory path does not exists or is not a directory
   */
  public File getSchemaDirectoryPath() throws InitializationException
  {
    final File dir = serverContext.getEnvironment().getSchemaDirectory();
    if (dir == null)
    {
      throw new InitializationException(ERR_CONFIG_SCHEMA_NO_SCHEMA_DIR.get(null));
    }
    if (!dir.exists())
    {
      throw new InitializationException(ERR_CONFIG_SCHEMA_NO_SCHEMA_DIR.get(dir.getPath()));
    }
    if (!dir.isDirectory())
    {
      throw new InitializationException(ERR_CONFIG_SCHEMA_DIR_NOT_DIRECTORY.get(dir.getPath()));
    }
    return dir;
  }

  /**
   * Updates the schema using the provided schema updater.
   *
   * @param updater
   *          the updater that performs modifications on the schema builder
   * @throws DirectoryException if there is any problem updating the schema
   */
  public void updateSchema(SchemaUpdater updater) throws DirectoryException
  {
    exclusiveLock.lock();
    try
    {
      SchemaBuilder schemaBuilder = new SchemaBuilder(schema);
      updater.update(schemaBuilder);
      switchSchema(schemaBuilder.toSchema());
    }
    finally
    {
      exclusiveLock.unlock();
    }
  }

  /**
   * Replaces the schema with the provided schema.
   *
   * @param schema
   *          the new schema to use
   * @throws DirectoryException
   *            if the provided schema contains warnings
   */
  public void updateSchema(Schema schema) throws DirectoryException
  {
    exclusiveLock.lock();
    try
    {
      switchSchema(schema);
    }
    finally
    {
      exclusiveLock.unlock();
    }
  }

  /**
   * Replaces the schema with the provided schema and update provided schema files.
   *
   * @param newSchema
   *            The new schema to use
   * @param newExtraAttributes
   *            The new map of extra attributes
   * @param modifiedSchemaFileNames
   *            The set of names of schema files that need to be updated
   * @param alertGenerator
   *            The generator to use for alerts
   * @throws DirectoryException
   *            If an error occurs during update of schema or schema files
   */
  public void updateSchemaAndSchemaFiles(Schema newSchema, Map<String, Attribute> newExtraAttributes,
      TreeSet<String> modifiedSchemaFileNames, AlertGenerator alertGenerator) throws DirectoryException
  {
    exclusiveLock.lock();
    try
    {
      switchSchema(newSchema);
      this.extraAttributes = newExtraAttributes;
      schemaWriter.updateSchemaFiles(schema, newExtraAttributes.values(), modifiedSchemaFileNames, alertGenerator);
      youngestModificationTime = System.currentTimeMillis();
    }
    finally
    {
      exclusiveLock.unlock();
    }
  }

  /**
   * Replaces the schema with the provided schema and update the concatened schema files.
   *
   * @param newSchema
   *            The new schema to use
   * @throws DirectoryException
   *            If an error occurs during update of schema or schema files
   */
  public void updateSchemaAndConcatenatedSchemaFiles(Schema newSchema) throws DirectoryException
  {
    exclusiveLock.lock();
    try
    {
      switchSchema(newSchema);
      schemaWriter.writeConcatenatedSchema();
      youngestModificationTime = System.currentTimeMillis();
    }
    finally
    {
      exclusiveLock.unlock();
    }
  }

  /**
   * Updates the schema option  if the new value differs from the old value.
   *
   * @param <T> the schema option's type
   * @param option the schema option to update
   * @param newValue the new value for the schema option
   * @throws DirectoryException if there is any problem updating the schema
   */
  public <T> void updateSchemaOption(final Option<T> option, final T newValue) throws DirectoryException
  {
    final T oldValue = schema.getOption(option);
    if (!oldValue.equals(newValue))
    {
      updateSchema(new SchemaUpdater()
      {
        @Override
        public void update(SchemaBuilder builder)
        {
          builder.setOption(option, newValue);
        }
      });
    }
  }

  /**
   * Returns the extra attributes stored in this schema handler.
   *
   * @return  The extra attributes.
   */
  public Map<String, Attribute> getExtraAttributes()
  {
    return new HashMap<>(extraAttributes);
  }

  /** Takes an exclusive lock on the schema. */
  public void exclusiveLock()
  {
    exclusiveLock.lock();
  }

  /** Releases an exclusive lock on the schema. */
  public void exclusiveUnlock()
  {
    exclusiveLock.unlock();
  }

  /**
   * Destroys the structures maintained by this handler so that they are no longer usable.
   * <p>
   * This should only be called at the end of the server shutdown process, and it can help detect
   * inappropriate cached references.
   */
  public void destroy()
  {
    exclusiveLock.lock();
    try
    {
      if (schema != null)
      {
        schema = null;
      }
      if (extraAttributes != null)
      {
        extraAttributes.clear();
        extraAttributes = null;
      }
    }
    finally
    {
      exclusiveLock.unlock();
    }
  }

  /**
   * Imports the provided schema entry in the schema.
   * <p>
   * The behavior is:
   * <ul>
   *  <li>iterate over each element of the newSchemaEntry and compare with the existing schema</li>
   *  <li>if the new schema element does not exist in current schema, add it to the schema</li>
   *  <li>if an element of current schema is not in the new schema entry: delete it</li>
   * </ul>
   * <p>
   * FIXME: currently, attributeTypes and objectClasses are the only elements taken into account.
   *
   * @param newSchemaEntry
   *          The schema entry to be imported.
   * @param alertGenerator
   *          Alert generator to use.
   * @throws DirectoryException
   *           If an error occurs during the import
   */
  public void importEntry(org.opends.server.types.Entry newSchemaEntry, AlertGenerator alertGenerator)
      throws DirectoryException
  {
    // work on a fixed schema version
    Schema currentSchema = schema;
    SchemaBuilder newSchemaBuilder = new SchemaBuilder(currentSchema);
    TreeSet<String> modifiedSchemaFiles = new TreeSet<>();

    // loop on the attribute types in the entry just received
    // and add them in the existing schema.
    Set<String> oidList = new HashSet<>(1000);
    for (Attribute a : newSchemaEntry.getAllAttributes(attributeTypesType))
    {
      // Look for attribute types that could have been added to the schema
      // or modified in the schema
      for (ByteString v : a)
      {
        String definition = v.toString();
        String schemaFile = SchemaUtils.parseSchemaFileFromElementDefinition(definition);
        if (is02ConfigLdif(schemaFile))
        {
          // Do not import the file containing the definitions of the Schema elements used for configuration
          // because these definitions may vary between versions of OpenDJ.
          continue;
        }

        String oid = SchemaUtils.parseAttributeTypeOID(definition);
        oidList.add(oid);
        try
        {
          // Register this attribute type in the new schema
          // unless it is already defined with the same syntax.
          if (hasAttributeTypeDefinitionChanged(currentSchema, oid, definition))
          {
            newSchemaBuilder.addAttributeType(definition, true);
            addElementIfNotNull(modifiedSchemaFiles, schemaFile);
          }
        }
        catch (Exception e)
        {
          logger.info(NOTE_SCHEMA_IMPORT_FAILED, definition, e.getMessage());
        }
      }
    }

    // loop on all the attribute types in the current schema and delete
    // them from the new schema if they are not in the imported schema entry.
    for (AttributeType removeType : currentSchema.getAttributeTypes())
    {
      String schemaFile = getElementSchemaFile(removeType);
      if (is02ConfigLdif(schemaFile) || CORE_SCHEMA_ELEMENTS_FILE.equals(schemaFile))
      {
        // Also never delete anything from the core schema file.
        continue;
      }
      if (!oidList.contains(removeType.getOID()))
      {
        newSchemaBuilder.removeAttributeType(removeType.getOID());
        addElementIfNotNull(modifiedSchemaFiles, schemaFile);
      }
    }

    // loop on the objectClasses from the entry, search if they are
    // already in the current schema, add them if not.
    oidList.clear();
    for (Attribute a : newSchemaEntry.getAllAttributes(objectClassesType))
    {
      for (ByteString v : a)
      {
        // It IS important here to allow the unknown elements that could
        // appear in the new config schema.
        String definition = v.toString();
        String schemaFile = SchemaUtils.parseSchemaFileFromElementDefinition(definition);
        if (is02ConfigLdif(schemaFile))
        {
          continue;
        }
        String oid = SchemaUtils.parseObjectClassOID(definition);
        oidList.add(oid);
        try
        {
          // Register this ObjectClass in the new schema
          // unless it is already defined with the same syntax.
          if (hasObjectClassDefinitionChanged(currentSchema, oid, definition))
          {
            newSchemaBuilder.addObjectClass(definition, true);
            addElementIfNotNull(modifiedSchemaFiles, schemaFile);
          }
        }
        catch (Exception e)
        {
          logger.info(NOTE_SCHEMA_IMPORT_FAILED, definition, e.getMessage());
        }
      }
    }

    // loop on all the object classes in the current schema and delete
    // them from the new schema if they are not in the imported schema entry.
    for (ObjectClass removeClass : currentSchema.getObjectClasses())
    {
      String schemaFile = getElementSchemaFile(removeClass);
      if (is02ConfigLdif(schemaFile))
      {
        continue;
      }
      if (!oidList.contains(removeClass.getOID()))
      {
        newSchemaBuilder.removeObjectClass(removeClass.getOID());
        addElementIfNotNull(modifiedSchemaFiles, schemaFile);
      }
    }

    // Finally, if there were some modifications, save the new schema
    if (!modifiedSchemaFiles.isEmpty())
    {
      Schema newSchema = newSchemaBuilder.toSchema();
      updateSchemaAndSchemaFiles(newSchema, getExtraAttributes(), modifiedSchemaFiles, alertGenerator);
    }
  }

  private boolean is02ConfigLdif(String schemaFile)
  {
    return CONFIG_SCHEMA_ELEMENTS_FILE.equals(schemaFile);
  }

  private <T> void addElementIfNotNull(Collection<T> col, T element)
  {
    if (element != null)
    {
      col.add(element);
    }
  }

  private boolean hasAttributeTypeDefinitionChanged(Schema schema, String oid, String definition)
  {
    if (schema.hasAttributeType(oid))
    {
      AttributeType oldAttrType = schema.getAttributeType(oid);
      return !oldAttrType.toString().equals(definition);

    }
    return true;
  }

  private boolean hasObjectClassDefinitionChanged(Schema schema, String oid, String definition)
  {
    if (schema.hasObjectClass(oid))
    {
      ObjectClass oldObjectClass = schema.getObjectClass(oid);
      return !oldObjectClass.toString().equals(definition);
    }
    return true;
  }

  /**
   * Load the schema from provided root configuration.
   *
   * @param rootConfiguration
   *          The root to retrieve schema provider configurations.
   * @param schemaBuilder
   *          The schema builder that providers should update.
   * @param schemaUpdater
   *          The updater that providers should use when applying a configuration change.
   */
  private void loadSchemaFromProviders(final RootCfg rootConfiguration, final SchemaBuilder schemaBuilder)
      throws ConfigException, InitializationException {
    for (final String name : rootConfiguration.listSchemaProviders())
    {
      final SchemaProviderCfg config = rootConfiguration.getSchemaProvider(name);
      if (config.isEnabled())
      {
        loadSchemaProvider(config.getJavaClass(), config, schemaBuilder, true);
      }
      else if (name.equals(CORE_SCHEMA_PROVIDER_NAME))
      {
        throw new ConfigException(ERR_CONFIG_CORE_SCHEMA_PROVIDER_DISABLED.get(config.dn()));
      }
    }
  }

  /**
   * Load the schema provider from the provided class name.
   * <p>
   * If {@code} initialize} is {@code true}, then the provider is initialized,
   * and the provided schema builder is updated with schema elements from the provider.
   */
  private <T extends SchemaProviderCfg> SchemaProvider<T> loadSchemaProvider(final String className,
      final T config, final SchemaBuilder schemaBuilder, final boolean initialize)
      throws InitializationException
  {
    try
    {
      final ClassPropertyDefinition propertyDef = SchemaProviderCfgDefn.getInstance().getJavaClassPropertyDefinition();
      final Class<? extends SchemaProvider> providerClass = propertyDef.loadClass(className, SchemaProvider.class);
      final SchemaProvider<T> provider = providerClass.newInstance();

      if (initialize)
      {
        provider.initialize(serverContext, config, schemaBuilder);
      }
      else
      {
        final List<LocalizableMessage> unacceptableReasons = new ArrayList<>();
        if (!provider.isConfigurationAcceptable(config, unacceptableReasons))
        {
          final String reasons = Utils.joinAsString(".  ", unacceptableReasons);
          throw new InitializationException(ERR_CONFIG_SCHEMA_PROVIDER_CONFIG_NOT_ACCEPTABLE.get(config.dn(), reasons));
        }
      }
      return provider;
    }
    catch (Exception e)
    {
      throw new InitializationException(ERR_CONFIG_SCHEMA_PROVIDER_CANT_BE_INITIALIZED.get(
          className, config.dn(), stackTraceToSingleLineString(e)), e);
    }
  }

  /** Returns the LDIF reader on provided LDIF file. The caller must ensure the reader is closed. */
  private EntryReader getLDIFReader(final File ldifFile, final Schema schema)
      throws InitializationException
  {
    try
    {
      final LDIFEntryReader reader = new LDIFEntryReader(new FileReader(ldifFile));
      reader.setSchema(schema);
      reader.setSchemaValidationPolicy(SchemaValidationPolicy.ignoreAll());
      return reader;
    }
    catch (FileNotFoundException e)
    {
      throw new InitializationException(WARN_CONFIG_SCHEMA_CANNOT_OPEN_FILE.get(ldifFile.getPath(),
          ldifFile.getParent(), StaticUtils.getExceptionMessage(e)));
    }
  }

  /**
   * Completes the schema with schema files.
   *
   * @param schemaBuilder
   *          The schema builder to update with the content of the schema files.
   * @throws ConfigException
   *           If a configuration problem causes the schema element
   *           initialization to fail.
   * @throws InitializationException
   *           If a problem occurs while initializing the schema elements that
   *           is not related to the server configuration.
   */
  private void completeSchemaFromFiles(final SchemaBuilder schemaBuilder)
      throws ConfigException, InitializationException
  {
    final File schemaDirectory = getSchemaDirectoryPath();
    final File[] schemaFiles = getSchemaFiles(schemaDirectory);
    final List<String> schemaFileNames = getSchemaFileNames(schemaFiles);
    updateModificationTimes(schemaFiles);

    for (String schemaFile : schemaFileNames)
    {
      loadSchemaFile(new File(schemaDirectory, schemaFile), schemaBuilder, Schema.getDefaultSchema(), false);
    }
  }

  private File[] getSchemaFiles(File schemaDirectory) throws InitializationException
  {
    try
    {
      return schemaDirectory.listFiles(new SchemaFileFilter());
    }
    catch (Exception e)
    {
      logger.traceException(e);
      throw new InitializationException(
          ERR_CONFIG_SCHEMA_CANNOT_LIST_FILES.get(schemaDirectory, getExceptionMessage(e)), e);
    }
  }

  /** Returns the sorted list of names of schema files contained in the provided directory. */
  private List<String> getSchemaFileNames(final File[] schemaFiles)
  {
    final List<String> schemaFileNames = new ArrayList<>(schemaFiles.length);
    for (final File f : schemaFiles)
    {
      if (f.isFile())
      {
        schemaFileNames.add(f.getName());
      }
    }
    Collections.sort(schemaFileNames);
    return schemaFileNames;
  }

  private void updateModificationTimes(final File[] schemaFiles)
  {
    for (final File file : schemaFiles)
    {

      final long modificationTime = file.lastModified();
      if (oldestModificationTime <= 0L
          || oldestModificationTime >= modificationTime)
      {
        oldestModificationTime = modificationTime;
      }

      if (youngestModificationTime <= 0
          || youngestModificationTime <= modificationTime)
      {
        youngestModificationTime = modificationTime;
      }
    }
    // If the oldest and youngest modification timestamps didn't get set
    // then set them to the current time.
    if (oldestModificationTime <= 0)
    {
      oldestModificationTime = System.currentTimeMillis();
    }

    if (youngestModificationTime <= 0)
    {
      youngestModificationTime = oldestModificationTime;
    }
  }

  /** Returns the schema entry from the provided reader, which may be {@code null} if file is empty. */
  private Entry readSchemaEntry(final EntryReader reader, final File schemaFile) throws InitializationException {
    try
    {
      if (!reader.hasNext())
      {
        // empty file, just skip it
        return null;
      }
      Entry entry = reader.readEntry();
      if (reader.hasNext())
      {
        logger.warn(WARN_CONFIG_SCHEMA_MULTIPLE_ENTRIES_IN_FILE, schemaFile.getPath(), schemaFile.getParent());
      }
      return entry;
    }
    catch (IOException e)
    {
      throw new InitializationException(WARN_CONFIG_SCHEMA_CANNOT_READ_LDIF_ENTRY.get(
              schemaFile.getPath(), schemaFile.getParent(), getExceptionMessage(e)), e);
    }
    finally
    {
      closeSilently(reader);
    }
  }

  /**
   * Loads the contents of the provided schema file into the provided schema builder.
   *
   * @param schemaFile
   *            The schema file to load.
   * @param schemaBuilder
   *            The schema builder to update.
   * @param readSchema
   *            The schema used to read the schema file.
   * @throws InitializationException
   *            If a problem occurs when reading the schema file.
   * @throws ConfigException
   *            If a problem occurs when updating the schema builder.
   */
  public void loadSchemaFileIntoSchemaBuilder(final File schemaFile, final SchemaBuilder schemaBuilder,
      final Schema readSchema) throws InitializationException, ConfigException
  {
    loadSchemaFile(schemaFile, schemaBuilder, readSchema, true);
  }

  /**
   * Loads the contents of the provided schema file into the provided schema builder and returns the list
   * of modifications.
   *
   * @param schemaFile
   *          The schema file to load.
   * @param schemaBuilder
   *            The schema builder to update.
   * @param readSchema
   *            The schema used to read the schema file.
   * @return A list of the modifications that could be performed in order to obtain the contents of
   *         the file.
   * @throws ConfigException
   *           If a configuration problem causes the schema element initialization to fail.
   * @throws InitializationException
   *           If a problem occurs while initializing the schema elements that is not related to the
   *           server configuration.
   */
  public List<Modification> loadSchemaFileIntoSchemaBuilderAndReturnModifications(final File schemaFile,
      final SchemaBuilder schemaBuilder, final Schema readSchema) throws InitializationException, ConfigException
  {
    final Entry entry = loadSchemaFile(schemaFile, schemaBuilder, readSchema, true);
    if (entry != null)
    {
      return createAddModifications(entry,
          ldapSyntaxesType,
          attributeTypesType,
          objectClassesType,
          nameFormsType,
          ditContentRulesType,
          ditStructureRulesType,
          matchingRuleUsesType);
    }
    return Collections.emptyList();
  }

  private List<Modification> createAddModifications(Entry entry, AttributeType... attrTypes)
  {
    List<Modification> mods = new ArrayList<>(entry.getAttributeCount());
    for (AttributeType attrType : attrTypes)
    {
      for (org.forgerock.opendj.ldap.Attribute a : entry.getAllAttributes(AttributeDescription.create(attrType)))
      {
        mods.add(new Modification(ModificationType.ADD, Converters.toAttribute(a)));
      }
    }
    return mods;
  }

  /**
   * Add the schema from the provided schema file to the provided schema
   * builder.
   *
   * @param schemaFile
   *          the schema file to be loaded
   * @param schemaBuilder
   *          The schema builder in which the contents of the schema file are to
   *          be loaded.
   * @param readSchema
   *          The schema used to read the file.
   * @param failOnError
   *          Indicates whether an exception must be thrown if an error occurs
   * @return the schema entry that has been read from the schema file
   * @throws InitializationException
   *           If a problem occurs while initializing the schema elements.
   * @throws ConfigException
   *           If a problem occurs when updating the schema builder.
   */
  private Entry loadSchemaFile(final File schemaFile, final SchemaBuilder schemaBuilder, final Schema readSchema,
      boolean failOnError)
         throws InitializationException, ConfigException
  {
    EntryReader reader = null;
    try
    {
      reader = getLDIFReader(schemaFile, readSchema);
      final Entry entry = readSchemaEntry(reader, schemaFile);
      if (entry != null)
      {
        updateSchemaBuilderWithEntry(schemaBuilder, entry, schemaFile.getName(), failOnError);
      }
      return entry;
    }
    finally {
      Utils.closeSilently(reader);
    }
  }

  private void updateSchemaBuilderWithEntry(SchemaBuilder schemaBuilder, Entry schemaEntry, String schemaFile,
      boolean failOnError) throws ConfigException
  {

    // immediately overwrite these definitions which are already defined in the SDK core schema
    final boolean overwriteCoreSchemaDefinitions =
        CORE_SCHEMA_FILE.equals(schemaFile) || RFC_3112_SCHEMA_FILE.equals(schemaFile);

    updateSchemaBuilderWithEntry0(schemaBuilder, schemaEntry, schemaFile, overwriteCoreSchemaDefinitions);

    // check that the update is correct
    Collection<LocalizableMessage> warnings = schemaBuilder.toSchema().getWarnings();
    if (!warnings.isEmpty())
    {
      if (!overwriteCoreSchemaDefinitions)
      {
        logger.warn(WARN_CONFIG_SCHEMA_FILE_HAS_SCHEMA_WARNING, schemaFile, warnings);
        // try to update again with overwriting
        updateSchemaBuilderWithEntry0(schemaBuilder, schemaEntry, schemaFile, true);
        warnings = schemaBuilder.toSchema().getWarnings();
        if (!warnings.isEmpty())
        {
          reportSchemaWarnings(WARN_CONFIG_SCHEMA_FILE_HAS_SCHEMA_WARNING_WITH_OVERWRITE.get(schemaFile, warnings),
              failOnError);
        }
      }
      else
      {
        reportSchemaWarnings(WARN_CONFIG_SCHEMA_FILE_HAS_SCHEMA_WARNING_WITH_OVERWRITE.get(schemaFile, warnings),
            failOnError);
      }
    }
  }

  private void updateSchemaBuilderWithEntry0(final SchemaBuilder schemaBuilder, final Entry schemaEntry,
      final String schemaFile, final boolean overwrite)
  {
    schemaBuilder.addSchema(schemaEntry, overwrite, new SchemaBuilderHook()
    {
      @Override
      public void beforeAddSyntax(Syntax.Builder builder)
      {
        builder.removeExtraProperty(SCHEMA_PROPERTY_FILENAME).extraProperties(SCHEMA_PROPERTY_FILENAME, schemaFile);
      }

      @Override
      public void beforeAddObjectClass(ObjectClass.Builder builder)
      {
        builder.removeExtraProperty(SCHEMA_PROPERTY_FILENAME).extraProperties(SCHEMA_PROPERTY_FILENAME, schemaFile);
      }

      @Override
      public void beforeAddNameForm(NameForm.Builder builder)
      {
        builder.removeExtraProperty(SCHEMA_PROPERTY_FILENAME).extraProperties(SCHEMA_PROPERTY_FILENAME, schemaFile);
      }

      @Override
      public void beforeAddMatchingRuleUse(MatchingRuleUse.Builder builder)
      {
        builder.removeExtraProperty(SCHEMA_PROPERTY_FILENAME).extraProperties(SCHEMA_PROPERTY_FILENAME, schemaFile);
      }

      @Override
      public void beforeAddMatchingRule(MatchingRule.Builder builder)
      {
        builder.removeExtraProperty(SCHEMA_PROPERTY_FILENAME).extraProperties(SCHEMA_PROPERTY_FILENAME, schemaFile);
      }

      @Override
      public void beforeAddDitStructureRule(DITStructureRule.Builder builder)
      {
        builder.removeExtraProperty(SCHEMA_PROPERTY_FILENAME).extraProperties(SCHEMA_PROPERTY_FILENAME, schemaFile);
      }

      @Override
      public void beforeAddDitContentRule(DITContentRule.Builder builder)
      {
        builder.removeExtraProperty(SCHEMA_PROPERTY_FILENAME).extraProperties(SCHEMA_PROPERTY_FILENAME, schemaFile);
      }

      @Override
      public void beforeAddAttribute(Builder builder)
      {
        builder.removeExtraProperty(SCHEMA_PROPERTY_FILENAME).extraProperties(SCHEMA_PROPERTY_FILENAME, schemaFile);
      }
    });
  }

  private void switchSchema(Schema newSchema) throws DirectoryException
  {
    rejectSchemaWithWarnings(newSchema);
    schema = newSchema.asNonStrictSchema();
    Schema.setDefaultSchema(schema);
  }

  private void rejectSchemaWithWarnings(Schema newSchema) throws DirectoryException
  {
    Collection<LocalizableMessage> warnings = newSchema.getWarnings();
    if (!warnings.isEmpty())
    {
      throw new DirectoryException(ResultCode.CONSTRAINT_VIOLATION,
          ERR_SCHEMA_HAS_WARNINGS.get(warnings.size(), Utils.joinAsString("; ", warnings)));
    }
  }

  private void reportSchemaWarnings(LocalizableMessage message, boolean failOnError) throws ConfigException
  {
    if (failOnError)
    {
      throw new ConfigException(message);
    }
    logger.error(message);
  }

  /** A file filter implementation that accepts only LDIF files. */
  public static class SchemaFileFilter implements FilenameFilter
  {
    private static final String LDIF_SUFFIX = ".ldif";

    @Override
    public boolean accept(File directory, String filename)
    {
      return OperatingSystem.isWindows() ?
          filename.toLowerCase().endsWith(LDIF_SUFFIX) : filename.endsWith(LDIF_SUFFIX);
    }
  }

  /** Interface to update a schema provided a schema builder. */
  public interface SchemaUpdater
  {
    /**
     * Updates the schema using the provided schema builder.
     *
     * @param builder
     *          The builder on the current schema
     * @throws DirectoryException
     *          If an error occurs during the schema update
     */
    void update(SchemaBuilder builder) throws DirectoryException;
  }
}
