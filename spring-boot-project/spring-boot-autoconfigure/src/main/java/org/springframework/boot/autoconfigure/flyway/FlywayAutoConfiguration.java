/*
 * Copyright 2012-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.autoconfigure.flyway;

import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.flywaydb.database.sqlserver.SQLServerConfigurationExtension;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.FlywayAutoConfigurationRuntimeHints;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.FlywayDataSourceCondition;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for Flyway database migrations.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Vedran Pavic
 * @author Stephane Nicoll
 * @author Jacques-Etienne Beaudet
 * @author Eddú Meléndez
 * @author Dominic Gunn
 * @author Dan Zheng
 * @author András Deák
 * @author Semyon Danilov
 * @author Chris Bono
 * @author Moritz Halbritter
 * @since 1.1.0
 */
@AutoConfiguration(after = { DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class })
@ConditionalOnClass(Flyway.class)
@Conditional(FlywayDataSourceCondition.class)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", matchIfMissing = true)
@Import(DatabaseInitializationDependencyConfigurer.class)
@ImportRuntimeHints(FlywayAutoConfigurationRuntimeHints.class)
public class FlywayAutoConfiguration {

	@Bean
	@ConfigurationPropertiesBinding
	public StringOrNumberToMigrationVersionConverter stringOrNumberMigrationVersionConverter() {
		return new StringOrNumberToMigrationVersionConverter();
	}

	@Bean
	public FlywaySchemaManagementProvider flywayDefaultDdlModeProvider(ObjectProvider<Flyway> flyways) {
		return new FlywaySchemaManagementProvider(flyways);
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(JdbcUtils.class)
	@ConditionalOnMissingBean(Flyway.class)
	@EnableConfigurationProperties(FlywayProperties.class)
	public static class FlywayConfiguration {

		@Bean
		ResourceProviderCustomizer resourceProviderCustomizer() {
			return new ResourceProviderCustomizer();
		}

		@Deprecated(since = "3.0.0", forRemoval = true)
		public Flyway flyway(FlywayProperties properties, ResourceLoader resourceLoader,
				ObjectProvider<DataSource> dataSource, ObjectProvider<DataSource> flywayDataSource,
				ObjectProvider<FlywayConfigurationCustomizer> fluentConfigurationCustomizers,
				ObjectProvider<JavaMigration> javaMigrations, ObjectProvider<Callback> callbacks) {
			return flyway(properties, resourceLoader, dataSource, flywayDataSource, fluentConfigurationCustomizers,
					javaMigrations, callbacks, new ResourceProviderCustomizer());
		}

		@Bean
		Flyway flyway(FlywayProperties properties, ResourceLoader resourceLoader, ObjectProvider<DataSource> dataSource,
				@FlywayDataSource ObjectProvider<DataSource> flywayDataSource,
				ObjectProvider<FlywayConfigurationCustomizer> fluentConfigurationCustomizers,
				ObjectProvider<JavaMigration> javaMigrations, ObjectProvider<Callback> callbacks,
				ResourceProviderCustomizer resourceProviderCustomizer) {
			FluentConfiguration configuration = new FluentConfiguration(resourceLoader.getClassLoader());
			configureDataSource(configuration, properties, flywayDataSource.getIfAvailable(), dataSource.getIfUnique());
			configureProperties(configuration, properties);
			configureCallbacks(configuration, callbacks.orderedStream().toList());
			configureJavaMigrations(configuration, javaMigrations.orderedStream().toList());
			fluentConfigurationCustomizers.orderedStream().forEach((customizer) -> customizer.customize(configuration));
			resourceProviderCustomizer.customize(configuration);
			return configuration.load();
		}

		private void configureDataSource(FluentConfiguration configuration, FlywayProperties properties,
				DataSource flywayDataSource, DataSource dataSource) {
			DataSource migrationDataSource = getMigrationDataSource(properties, flywayDataSource, dataSource);
			configuration.dataSource(migrationDataSource);
		}

		private DataSource getMigrationDataSource(FlywayProperties properties, DataSource flywayDataSource,
				DataSource dataSource) {
			if (flywayDataSource != null) {
				return flywayDataSource;
			}
			if (properties.getUrl() != null) {
				DataSourceBuilder<?> builder = DataSourceBuilder.create().type(SimpleDriverDataSource.class);
				builder.url(properties.getUrl());
				applyCommonBuilderProperties(properties, builder);
				return builder.build();
			}
			if (properties.getUser() != null && dataSource != null) {
				DataSourceBuilder<?> builder = DataSourceBuilder.derivedFrom(dataSource)
					.type(SimpleDriverDataSource.class);
				applyCommonBuilderProperties(properties, builder);
				return builder.build();
			}
			Assert.state(dataSource != null, "Flyway migration DataSource missing");
			return dataSource;
		}

		private void applyCommonBuilderProperties(FlywayProperties properties, DataSourceBuilder<?> builder) {
			builder.username(properties.getUser());
			builder.password(properties.getPassword());
			if (StringUtils.hasText(properties.getDriverClassName())) {
				builder.driverClassName(properties.getDriverClassName());
			}
		}

		/**
		 * Configure the given {@code configuration} using the given {@code properties}.
		 * <p>
		 * To maximize forwards- and backwards-compatibility method references are not
		 * used.
		 * @param configuration the configuration
		 * @param properties the properties
		 */
		private void configureProperties(FluentConfiguration configuration, FlywayProperties properties) {
			PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
			String[] locations = new LocationResolver(configuration.getDataSource())
				.resolveLocations(properties.getLocations())
				.toArray(new String[0]);
			configuration.locations(locations);
			map.from(properties.isFailOnMissingLocations())
				.to((failOnMissingLocations) -> configuration.failOnMissingLocations(failOnMissingLocations));
			map.from(properties.getEncoding()).to((encoding) -> configuration.encoding(encoding));
			map.from(properties.getConnectRetries())
				.to((connectRetries) -> configuration.connectRetries(connectRetries));
			map.from(properties.getConnectRetriesInterval())
				.as(Duration::getSeconds)
				.as(Long::intValue)
				.to((connectRetriesInterval) -> configuration.connectRetriesInterval(connectRetriesInterval));
			map.from(properties.getLockRetryCount())
				.to((lockRetryCount) -> configuration.lockRetryCount(lockRetryCount));
			map.from(properties.getDefaultSchema()).to((schema) -> configuration.defaultSchema(schema));
			map.from(properties.getSchemas())
				.as(StringUtils::toStringArray)
				.to((schemas) -> configuration.schemas(schemas));
			map.from(properties.isCreateSchemas()).to((createSchemas) -> configuration.createSchemas(createSchemas));
			map.from(properties.getTable()).to((table) -> configuration.table(table));
			map.from(properties.getTablespace()).to((tablespace) -> configuration.tablespace(tablespace));
			map.from(properties.getBaselineDescription())
				.to((baselineDescription) -> configuration.baselineDescription(baselineDescription));
			map.from(properties.getBaselineVersion())
				.to((baselineVersion) -> configuration.baselineVersion(baselineVersion));
			map.from(properties.getInstalledBy()).to((installedBy) -> configuration.installedBy(installedBy));
			map.from(properties.getPlaceholders()).to((placeholders) -> configuration.placeholders(placeholders));
			map.from(properties.getPlaceholderPrefix())
				.to((placeholderPrefix) -> configuration.placeholderPrefix(placeholderPrefix));
			map.from(properties.getPlaceholderSuffix())
				.to((placeholderSuffix) -> configuration.placeholderSuffix(placeholderSuffix));
			map.from(properties.getPlaceholderSeparator())
				.to((placeHolderSeparator) -> configuration.placeholderSeparator(placeHolderSeparator));
			map.from(properties.isPlaceholderReplacement())
				.to((placeholderReplacement) -> configuration.placeholderReplacement(placeholderReplacement));
			map.from(properties.getSqlMigrationPrefix())
				.to((sqlMigrationPrefix) -> configuration.sqlMigrationPrefix(sqlMigrationPrefix));
			map.from(properties.getSqlMigrationSuffixes())
				.as(StringUtils::toStringArray)
				.to((sqlMigrationSuffixes) -> configuration.sqlMigrationSuffixes(sqlMigrationSuffixes));
			map.from(properties.getSqlMigrationSeparator())
				.to((sqlMigrationSeparator) -> configuration.sqlMigrationSeparator(sqlMigrationSeparator));
			map.from(properties.getRepeatableSqlMigrationPrefix())
				.to((repeatableSqlMigrationPrefix) -> configuration
					.repeatableSqlMigrationPrefix(repeatableSqlMigrationPrefix));
			map.from(properties.getTarget()).to((target) -> configuration.target(target));
			map.from(properties.isBaselineOnMigrate())
				.to((baselineOnMigrate) -> configuration.baselineOnMigrate(baselineOnMigrate));
			map.from(properties.isCleanDisabled()).to((cleanDisabled) -> configuration.cleanDisabled(cleanDisabled));
			map.from(properties.isCleanOnValidationError())
				.to((cleanOnValidationError) -> configuration.cleanOnValidationError(cleanOnValidationError));
			map.from(properties.isGroup()).to((group) -> configuration.group(group));
			map.from(properties.isMixed()).to((mixed) -> configuration.mixed(mixed));
			map.from(properties.isOutOfOrder()).to((outOfOrder) -> configuration.outOfOrder(outOfOrder));
			map.from(properties.isSkipDefaultCallbacks())
				.to((skipDefaultCallbacks) -> configuration.skipDefaultCallbacks(skipDefaultCallbacks));
			map.from(properties.isSkipDefaultResolvers())
				.to((skipDefaultResolvers) -> configuration.skipDefaultResolvers(skipDefaultResolvers));
			map.from(properties.isValidateMigrationNaming())
				.to((validateMigrationNaming) -> configuration.validateMigrationNaming(validateMigrationNaming));
			map.from(properties.isValidateOnMigrate())
				.to((validateOnMigrate) -> configuration.validateOnMigrate(validateOnMigrate));
			map.from(properties.getInitSqls())
				.whenNot(CollectionUtils::isEmpty)
				.as((initSqls) -> StringUtils.collectionToDelimitedString(initSqls, "\n"))
				.to((initSql) -> configuration.initSql(initSql));
			map.from(properties.getScriptPlaceholderPrefix())
				.to((prefix) -> configuration.scriptPlaceholderPrefix(prefix));
			map.from(properties.getScriptPlaceholderSuffix())
				.to((suffix) -> configuration.scriptPlaceholderSuffix(suffix));
			// Flyway Teams properties
			map.from(properties.getBatch()).to((batch) -> configuration.batch(batch));
			map.from(properties.getDryRunOutput()).to((dryRunOutput) -> configuration.dryRunOutput(dryRunOutput));
			map.from(properties.getErrorOverrides())
				.to((errorOverrides) -> configuration.errorOverrides(errorOverrides));
			map.from(properties.getLicenseKey()).to((licenseKey) -> configuration.licenseKey(licenseKey));
			map.from(properties.getOracleSqlplus()).to((oracleSqlplus) -> configuration.oracleSqlplus(oracleSqlplus));
			map.from(properties.getOracleSqlplusWarn())
				.to((oracleSqlplusWarn) -> configuration.oracleSqlplusWarn(oracleSqlplusWarn));
			map.from(properties.getOracleKerberosCacheFile())
				.to((oracleKerberosCacheFile) -> configuration.oracleKerberosCacheFile(oracleKerberosCacheFile));
			map.from(properties.getStream()).to((stream) -> configuration.stream(stream));
			map.from(properties.getUndoSqlMigrationPrefix())
				.to((undoSqlMigrationPrefix) -> configuration.undoSqlMigrationPrefix(undoSqlMigrationPrefix));
			map.from(properties.getCherryPick()).to((cherryPick) -> configuration.cherryPick(cherryPick));
			map.from(properties.getJdbcProperties())
				.whenNot(Map::isEmpty)
				.to((jdbcProperties) -> configuration.jdbcProperties(jdbcProperties));
			map.from(properties.getKerberosConfigFile())
				.to((configFile) -> configuration.kerberosConfigFile(configFile));
			map.from(properties.getOutputQueryResults())
				.to((outputQueryResults) -> configuration.outputQueryResults(outputQueryResults));
			map.from(properties.getSqlServerKerberosLoginFile())
				.whenNonNull()
				.to((sqlServerKerberosLoginFile) -> configureSqlServerKerberosLoginFile(configuration,
						sqlServerKerberosLoginFile));
			map.from(properties.getSkipExecutingMigrations())
				.to((skipExecutingMigrations) -> configuration.skipExecutingMigrations(skipExecutingMigrations));
			map.from(properties.getIgnoreMigrationPatterns())
				.whenNot(List::isEmpty)
				.to((ignoreMigrationPatterns) -> configuration
					.ignoreMigrationPatterns(ignoreMigrationPatterns.toArray(new String[0])));
			map.from(properties.getDetectEncoding())
				.to((detectEncoding) -> configuration.detectEncoding(detectEncoding));
		}

		private void configureSqlServerKerberosLoginFile(FluentConfiguration configuration,
				String sqlServerKerberosLoginFile) {
			SQLServerConfigurationExtension sqlServerConfigurationExtension = configuration.getPluginRegister()
				.getPlugin(SQLServerConfigurationExtension.class);
			Assert.state(sqlServerConfigurationExtension != null, "Flyway SQL Server extension missing");
			sqlServerConfigurationExtension.setKerberosLoginFile(sqlServerKerberosLoginFile);
		}

		private void configureCallbacks(FluentConfiguration configuration, List<Callback> callbacks) {
			if (!callbacks.isEmpty()) {
				configuration.callbacks(callbacks.toArray(new Callback[0]));
			}
		}

		private void configureJavaMigrations(FluentConfiguration flyway, List<JavaMigration> migrations) {
			if (!migrations.isEmpty()) {
				flyway.javaMigrations(migrations.toArray(new JavaMigration[0]));
			}
		}

		@Bean
		@ConditionalOnMissingBean
		public FlywayMigrationInitializer flywayInitializer(Flyway flyway,
				ObjectProvider<FlywayMigrationStrategy> migrationStrategy) {
			return new FlywayMigrationInitializer(flyway, migrationStrategy.getIfAvailable());
		}

	}

	private static class LocationResolver {

		private static final String VENDOR_PLACEHOLDER = "{vendor}";

		private final DataSource dataSource;

		LocationResolver(DataSource dataSource) {
			this.dataSource = dataSource;
		}

		List<String> resolveLocations(List<String> locations) {
			if (usesVendorLocation(locations)) {
				DatabaseDriver databaseDriver = getDatabaseDriver();
				return replaceVendorLocations(locations, databaseDriver);
			}
			return locations;
		}

		private List<String> replaceVendorLocations(List<String> locations, DatabaseDriver databaseDriver) {
			if (databaseDriver == DatabaseDriver.UNKNOWN) {
				return locations;
			}
			String vendor = databaseDriver.getId();
			return locations.stream().map((location) -> location.replace(VENDOR_PLACEHOLDER, vendor)).toList();
		}

		private DatabaseDriver getDatabaseDriver() {
			try {
				String url = JdbcUtils.extractDatabaseMetaData(this.dataSource, DatabaseMetaData::getURL);
				return DatabaseDriver.fromJdbcUrl(url);
			}
			catch (MetaDataAccessException ex) {
				throw new IllegalStateException(ex);
			}

		}

		private boolean usesVendorLocation(Collection<String> locations) {
			for (String location : locations) {
				if (location.contains(VENDOR_PLACEHOLDER)) {
					return true;
				}
			}
			return false;
		}

	}

	/**
	 * Convert a String or Number to a {@link MigrationVersion}.
	 */
	static class StringOrNumberToMigrationVersionConverter implements GenericConverter {

		private static final Set<ConvertiblePair> CONVERTIBLE_TYPES;

		static {
			Set<ConvertiblePair> types = new HashSet<>(2);
			types.add(new ConvertiblePair(String.class, MigrationVersion.class));
			types.add(new ConvertiblePair(Number.class, MigrationVersion.class));
			CONVERTIBLE_TYPES = Collections.unmodifiableSet(types);
		}

		@Override
		public Set<ConvertiblePair> getConvertibleTypes() {
			return CONVERTIBLE_TYPES;
		}

		@Override
		public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
			String value = ObjectUtils.nullSafeToString(source);
			return MigrationVersion.fromVersion(value);
		}

	}

	static final class FlywayDataSourceCondition extends AnyNestedCondition {

		FlywayDataSourceCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnBean(DataSource.class)
		private static final class DataSourceBeanCondition {

		}

		@ConditionalOnProperty(prefix = "spring.flyway", name = "url")
		private static final class FlywayUrlCondition {

		}

	}

	static class FlywayAutoConfigurationRuntimeHints implements RuntimeHintsRegistrar {

		@Override
		public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
			hints.resources().registerPattern("db/migration/*");
		}

	}

}
