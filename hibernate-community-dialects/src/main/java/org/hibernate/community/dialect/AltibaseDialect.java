/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.community.dialect;

import java.sql.*;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.TimeZone;

import jakarta.persistence.TemporalType;
import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.community.dialect.sequence.*;
import org.hibernate.dialect.*;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.community.dialect.pagination.AltibaseLimitHandler;
import org.hibernate.dialect.function.OracleTruncFunction;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelper;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelperBuilder;
import org.hibernate.engine.jdbc.env.spi.NameQualifierSupport;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockTimeoutException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.hibernate.internal.util.JdbcExceptionHelper;
import org.hibernate.query.sqm.*;
import org.hibernate.query.sqm.produce.function.FunctionParameterType;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.tool.schema.extract.spi.SequenceInformationExtractor;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.descriptor.java.PrimitiveByteArrayJavaType;
import org.hibernate.type.descriptor.sql.internal.CapacityDependentDdlType;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;
import org.hibernate.type.spi.TypeConfiguration;

import static org.hibernate.type.SqlTypes.*;
import static org.hibernate.type.descriptor.DateTimeUtils.JDBC_ESCAPE_END;
import static org.hibernate.type.descriptor.DateTimeUtils.JDBC_ESCAPE_START_TIMESTAMP;
import static org.hibernate.type.descriptor.DateTimeUtils.appendAsTimestampWithMicros;

/**
 * An SQL dialect for Altibase 7.3 and above.
 *
 * @author Geoffrey Park
 */
public class AltibaseDialect extends Dialect {

	private static final DatabaseVersion MINIMUM_VERSION = DatabaseVersion.make( 7, 3 );

	@SuppressWarnings("unused")
	public AltibaseDialect() {
		this( MINIMUM_VERSION );
	}

	public AltibaseDialect(DialectResolutionInfo info) {
		this( info.makeCopy() );
		registerKeywords( info );
	}

	public AltibaseDialect(DatabaseVersion version) {
		super( version );
	}

	@Override
	protected String columnType(int sqlTypeCode) {
		switch ( sqlTypeCode ) {
			case BOOLEAN:
				return "char(1)";
			case FLOAT:
			case DOUBLE:
				return "double";
			case TINYINT:
				return "smallint";
			case TIME:
			case TIMESTAMP:
			case TIME_WITH_TIMEZONE:
			case TIMESTAMP_WITH_TIMEZONE:
				return "date";
			case BINARY:
				return "byte($l)";
			case VARBINARY:
				return "varbyte($l)";
			case LONGVARBINARY:
				return "blob";
			case BIT:
				return "varbit($l)";
			case LONGVARCHAR:
			case NCLOB:
				return "clob";
			default:
				return super.columnType( sqlTypeCode );
		}
	}

	@Override
	protected void registerColumnTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.registerColumnTypes( typeContributions, serviceRegistry );
		final DdlTypeRegistry ddlTypeRegistry = typeContributions.getTypeConfiguration().getDdlTypeRegistry();

		ddlTypeRegistry.addDescriptor(
				CapacityDependentDdlType.builder( BINARY, columnType( LONGVARBINARY ), this )
						.withTypeCapacity( getMaxVarbinaryLength(), columnType( BINARY ) )
						.build()
		);
		ddlTypeRegistry.addDescriptor(
				CapacityDependentDdlType.builder( BIT, columnType( LONGVARBINARY ), this )
						.withTypeCapacity( 64000, columnType( BIT ) )
						.build()
		);
	}

	@Override
	public int getMaxVarcharLength() {
		return 32_000;
	}

	@Override
	public int getMaxVarbinaryLength() {
		return 32_000;
	}

	@Override
	public int getDefaultStatementBatchSize() {
		return 15;
	}

	@Override
	public String trimPattern(TrimSpec specification, boolean isWhitespace) {
		switch ( specification ) {
			case BOTH:
				return isWhitespace
						? "trim(?1)"
						: "trim(?1,?2)";
			case LEADING:
				return isWhitespace
						? "ltrim(?1)"
						: "ltrim(?1,?2)";
			case TRAILING:
				return isWhitespace
						? "rtrim(?1)"
						: "rtrim(?1,?2)";
		}

		return super.trimPattern( specification, isWhitespace );
	}

	@Override
	public int getPreferredSqlTypeCodeForBoolean() {
		return Types.BIT;
	}

	@Override
	public void initializeFunctionRegistry(FunctionContributions functionContributions) {
		super.initializeFunctionRegistry(functionContributions);
		final TypeConfiguration typeConfiguration = functionContributions.getTypeConfiguration();

		functionContributions.getFunctionRegistry().registerBinaryTernaryPattern(
				"locate",
				typeConfiguration.getBasicTypeRegistry().resolve( StandardBasicTypes.INTEGER ),
				"instr(?2,?1)",
				"instr(?2,?1,?3)",
				FunctionParameterType.STRING, FunctionParameterType.STRING, FunctionParameterType.INTEGER,
				typeConfiguration
		).setArgumentListSignature("(pattern, string[, start])");

		CommonFunctionFactory functionFactory = new CommonFunctionFactory(functionContributions);
		functionFactory.ceiling_ceil();
		functionFactory.trim2();
		functionFactory.stddev();
		functionFactory.variance();
		functionFactory.char_chr();
		functionFactory.concat_pipeOperator();
		functionFactory.coalesce();
		functionFactory.initcap();
		functionFactory.repeat_rpad();

		functionFactory.radians_acos();
		functionFactory.degrees_acos();

		functionFactory.ascii();
		functionFactory.toCharNumberDateTimestamp();
		functionFactory.lastDay();
		functionFactory.sysdate();
		functionFactory.rownum();
		functionFactory.instr();
		functionFactory.substr();
		functionFactory.cosh();
		functionFactory.sinh();
		functionFactory.tanh();
		functionFactory.log();
		functionFactory.log10_log();
		functionFactory.substring_substr();
		functionFactory.leftRight_substr();
		functionFactory.translate();
		functionFactory.addMonths();
		functionFactory.listagg( null );
		functionFactory.monthsBetween();
		functionFactory.windowFunctions();
		functionFactory.hypotheticalOrderedSetAggregates();
		functionFactory.bitLength_pattern( "bit_length(?1)", "lengthb(?1)*8" );
		functionFactory.octetLength_pattern( "octet_length(?1)", "lengthb(?1)" );
		functionContributions.getFunctionRegistry().register(
				"trunc",
				new OracleTruncFunction( functionContributions.getTypeConfiguration() )
		);
		functionContributions.getFunctionRegistry().registerAlternateKey( "truncate", "trunc" );
	}

	@Override
	public String currentDate() {
		return currentTimestamp();
	}

	@Override
	public String currentTime() {
		return currentTimestamp();
	}

	@Override
	public String currentTimestamp() {
		return "sysdate";
	}

	@Override
	public String currentLocalTime() {
		return currentTimestamp();
	}

	@Override
	public String currentLocalTimestamp() {
		return currentTimestamp();
	}

	@Override
	public String currentTimestampWithTimeZone() {
		return currentTimestamp();
	}

	@Override
	public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
		return new StandardSqlAstTranslatorFactory() {
			@Override
			protected <T extends JdbcOperation> SqlAstTranslator<T> buildTranslator(
					SessionFactoryImplementor sessionFactory, Statement statement) {
				return new AltibaseSqlAstTranslator<>( sessionFactory, statement );
			}
		};
	}

	@Override
	public long getFractionalSecondPrecisionInNanos() {
		return 1_000; //microseconds
	}

	/**
	 * Altibase supports a limited list of temporal fields in the
	 * extract() function, but we can emulate some of them by
	 * using to_char() with a format string instead of extract().
	 * Thus, the additional supported fields are
	 * {@link TemporalUnit#DAY_OF_YEAR},
	 * {@link TemporalUnit#DAY_OF_MONTH},
	 * {@link TemporalUnit#DAY_OF_YEAR},
	 * and {@link TemporalUnit#WEEK}.
	 */
	@Override
	public String extractPattern(TemporalUnit unit) {
		switch (unit) {
			case DAY_OF_WEEK:
				return "extract(?2, 'DAYOFWEEK')";
			case DAY_OF_MONTH:
				return "extract(?2, 'DAY')";
			case DAY_OF_YEAR:
				return "extract(?2,'DAYOFYEAR')";
			case WEEK:
				return "to_number(to_char(?2,'IW'))"; //the ISO week number
			case WEEK_OF_YEAR:
				return "extract(?2, 'WEEK')";
			case EPOCH:
				return timestampdiffPattern( TemporalUnit.SECOND, TemporalType.TIMESTAMP, TemporalType.TIMESTAMP )
						.replace( "?2", "TO_DATE('1970-01-01 00:00:00','YYYY-MM-DD HH24:MI:SS')" )
						.replace( "?3", "?2" );
			case QUARTER:
				return "extract(?2, 'QUARTER')";
			default:
				return super.extractPattern( unit );
		}
	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, TemporalType temporalType, IntervalType intervalType) {
		switch (unit) {
			case NANOSECOND:
				return "timestampadd(MICROSECOND,(?2)/1e3,?3)";
			case NATIVE:
				return "timestampadd(MICROSECOND, ?2, ?3)";
			default:
				return "timestampadd(?1, ?2, ?3)";
		}
	}

	@Override
	public String timestampdiffPattern(TemporalUnit unit, TemporalType fromTemporalType, TemporalType toTemporalType) {
		switch (unit) {
			case SECOND:
				return "datediff(?2, ?3, 'SECOND')";
			case NANOSECOND:
				return "datediff(?2, ?3, 'MICROSECOND')*1e3";
			case NATIVE:
				return "datediff(?2, ?3, 'MICROSECOND')";
			default:
				return "datediff(?2, ?3, '?1')";
		}
	}

	@Override
	public void appendBinaryLiteral(SqlAppender appender, byte[] bytes) {

		appender.appendSql( "VARBYTE'" );
		PrimitiveByteArrayJavaType.INSTANCE.appendString( appender, bytes );
		appender.appendSql( '\'' );
	}

	@Override
	public void appendDatetimeFormat(SqlAppender appender, String format) {
		appender.appendSql( OracleDialect.datetimeFormat( format, false, false ).result() );
	}

	@Override
	public String castPattern(CastType from, CastType to) {
		String result;
		switch ( to ) {
			case INTEGER:
			case LONG:
				result = BooleanDecoder.toInteger( from );
				if ( result != null ) {
					return result;
				}
				break;
			case INTEGER_BOOLEAN:
				result = BooleanDecoder.toIntegerBoolean( from );
				if ( result != null ) {
					return result;
				}
				break;
			case YN_BOOLEAN:
				result = BooleanDecoder.toYesNoBoolean( from );
				if ( result != null ) {
					return result;
				}
				break;
			case BOOLEAN:
			case TF_BOOLEAN:
				result = BooleanDecoder.toTrueFalseBoolean( from );
				if ( result != null ) {
					return result;
				}
				break;
			case STRING:
				switch ( from ) {
					case INTEGER_BOOLEAN:
					case TF_BOOLEAN:
					case YN_BOOLEAN:
						return BooleanDecoder.toString( from );
					case DATE:
						return "to_char(?1,'YYYY-MM-DD')";
					case TIME:
						return "to_char(?1,'HH24:MI:SS')";
					case TIMESTAMP:
					case OFFSET_TIMESTAMP:
					case ZONE_TIMESTAMP:
						return "to_char(?1,'YYYY-MM-DD HH24:MI:SS.FF6')";
				}
				break;
			case CLOB:
				// Altibase doesn't support cast to clob
				return "cast(?1 as varchar(32000))";
			case DATE:
				if ( from == CastType.STRING ) {
					return "to_date(?1,'YYYY-MM-DD')";
				}
				break;
			case TIME:
				if ( from == CastType.STRING ) {
					return "to_date(?1,'HH24:MI:SS')";
				}
				break;
			case TIMESTAMP:
			case OFFSET_TIMESTAMP:
			case ZONE_TIMESTAMP:
				if ( from == CastType.STRING ) {
					return "to_date(?1,'YYYY-MM-DD HH24:MI:SS.FF6')";
				}
				break;
		}
		return super.castPattern(from, to);
	}

	@Override
	public void appendDateTimeLiteral(
			SqlAppender appender,
			TemporalAccessor temporalAccessor,
			TemporalType precision,
			TimeZone jdbcTimeZone) {
		if (precision == TemporalType.TIMESTAMP) {
			appender.appendSql(JDBC_ESCAPE_START_TIMESTAMP);
			appendAsTimestampWithMicros(appender, temporalAccessor, supportsTemporalLiteralOffset(), jdbcTimeZone);
			appender.appendSql(JDBC_ESCAPE_END);
			return;
		}
		super.appendDateTimeLiteral(appender, temporalAccessor, precision, jdbcTimeZone);
	}

	@Override
	public void appendDateTimeLiteral(SqlAppender appender, Date date, TemporalType precision, TimeZone jdbcTimeZone) {
		if (precision == TemporalType.TIMESTAMP) {
			appender.appendSql(JDBC_ESCAPE_START_TIMESTAMP);
			appendAsTimestampWithMicros( appender, date, jdbcTimeZone );
			appender.appendSql(JDBC_ESCAPE_END);
			return;
		}
		super.appendDateTimeLiteral(appender, date, precision, jdbcTimeZone);
	}

	@Override
	public String translateDurationField(TemporalUnit unit) {
		//use microsecond as the "native" precision
		if ( unit == TemporalUnit.NATIVE ) {
			return "microsecond";
		}
		return super.translateDurationField( unit );
	}

	@Override
	public NullOrdering getNullOrdering() {
		return NullOrdering.LAST;
	}

	@Override
	public String getAddColumnString() {
		return "add column (";
	}

	@Override
	public String getAddColumnSuffixString() {
		return ")";
	}

	@Override
	public int getMaxIdentifierLength() {
		return 40;
	}

	@Override
	public IdentifierHelper buildIdentifierHelper(
			IdentifierHelperBuilder builder,
			DatabaseMetaData dbMetaData) throws SQLException {
		// Any use of keywords as identifiers will result in syntax error, so enable auto quote always
		builder.setAutoQuoteKeywords( true );
		builder.setAutoQuoteInitialUnderscore( false );
		builder.applyReservedWords( dbMetaData );

		return super.buildIdentifierHelper( builder, dbMetaData );
	}

	@Override
	public boolean canCreateSchema() {
		return false;
	}

	@Override
	public NameQualifierSupport getNameQualifierSupport() {
		return NameQualifierSupport.SCHEMA;
	}

	@Override
	public String[] getCreateSchemaCommand(String schemaName) {
		throw new UnsupportedOperationException( "No create schema syntax supported by " + getClass().getName() );
	}

	@Override
	public String[] getDropSchemaCommand(String schemaName) {
		throw new UnsupportedOperationException( "No drop schema syntax supported by " + getClass().getName() );
	}

	@Override
	public boolean qualifyIndexName() {
		return false;
	}

	@Override
	public boolean supportsInsertReturningGeneratedKeys() {
		return true;
	}

	@Override
	public boolean supportsTruncateWithCast(){
		return false;
	}

	@Override
	public boolean supportsCommentOn() {
		return true;
	}

	@Override
	public boolean supportsUnboundedLobLocatorMaterialization() {
		return false;
	}

	@Override
	public boolean supportsTupleDistinctCounts() {
		return false;
	}

	@Override
	public boolean supportsExistsInSelect() {
		return false;
	}

	@Override
	public boolean supportsPartitionBy() {
		return true;
	}

	@Override
	public SequenceSupport getSequenceSupport() {
		return AltibaseSequenceSupport.INSTANCE;
	}

	@Override
	public String getQuerySequencesString() {
		return "SELECT a.user_name USER_NAME, b.table_name SEQUENCE_NAME, c.current_seq CURRENT_VALUE, "
				+ "c.start_seq START_VALUE, c.min_seq MIN_VALUE, c.max_seq MAX_VALUE, c.increment_seq INCREMENT_BY, "
				+ "c.flag CYCLE_, c.sync_interval CACHE_SIZE "
				+ "FROM system_.sys_users_ a, system_.sys_tables_ b, x$seq c "
				+ "WHERE a.user_id = b.user_id AND b.table_oid = c.seq_oid AND a.user_name <> 'SYSTEM_' AND b.table_type = 'S' "
				+ "ORDER BY 1,2";
	}

	@Override
	public SequenceInformationExtractor getSequenceInformationExtractor() {
		return SequenceInformationExtractorAltibaseDatabaseImpl.INSTANCE;
	}

	@Override
	public LimitHandler getLimitHandler() {
		return AltibaseLimitHandler.INSTANCE;
	}

	@Override
	public boolean supportsCurrentTimestampSelection() {
		return true;
	}

	@Override
	public String getCurrentTimestampSelectString() {
		return "select sysdate from dual";
	}

	@Override
	public boolean isCurrentTimestampSelectStringCallable() {
		return false;
	}

	@Override
	public String getCascadeConstraintsString() {
		return " cascade constraints";
	}

	@Override
	public boolean supportsValuesListForInsert() {
		return false;
	}

	@Override
	public boolean supportsOrderByInSubquery() {
		return false;
	}

	@Override
	public boolean supportsWindowFunctions() {
		return true;
	}

	@Override
	public NationalizationSupport getNationalizationSupport() {
		return NationalizationSupport.IMPLICIT;
	}

	@Override
	public boolean supportsTemporaryTables() {
		return false;
	}

	@Override
	public boolean supportsTemporaryTablePrimaryKey() {
		return false;
	}

	@Override
	protected boolean supportsPredicateAsExpression() {
		return false;
	}

	@Override
	public String translateExtractField(TemporalUnit unit) {
		switch ( unit ) {
			case DAY_OF_MONTH: return "day";
			case DAY_OF_YEAR: return "dayofyear";
			case DAY_OF_WEEK: return "dayofweek";
			default: return super.translateExtractField( unit );
		}
	}

	@Override
	public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
		return (sqlException, message, sql) -> {

			final int errorCode = JdbcExceptionHelper.extractErrorCode(sqlException );

			if ( errorCode == 334393 || errorCode == 4164) {
				// 334393 - response timeout , 4164 - query timeout.
				return new LockTimeoutException(message, sqlException, sql );
			}

			// 200820 - Cannot insert NULL or update to NULL
			// 69720 - Unique constraint violated
			// 200823 - foreign key constraint violation
			// 200822 - failed on update or delete by foreign key constraint violation
			if ( errorCode == 200820 || errorCode == 69720 || errorCode == 200823 || errorCode == 200822 ) {
				final String constraintName = getViolatedConstraintNameExtractor().extractConstraintName( sqlException );

				return new ConstraintViolationException(message, sqlException, sql, constraintName );
			}

			return null;
		};
	}

}
