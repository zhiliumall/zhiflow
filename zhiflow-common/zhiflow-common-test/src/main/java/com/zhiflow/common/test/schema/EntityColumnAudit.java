package com.zhiflow.common.test.schema;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 「实体映射的列」与「版本化迁移建出的列」的比对工具（r3-B2 门禁的判据，r3 followup C7 抽到这里）。
 *
 * <p>商城实体分在两个可部署模块里：mall-admin（含 mall-common）与 mall-api（部分实体只在它那里）。
 * 两边各有一个门禁、各看各的类路径，判据只此一份，不各抄一遍。
 *
 * <ul>
 *   <li>实体列：用 MyBatis-Plus 自己的 {@link TableInfoHelper} 算（与运行时生成 SQL 同一套规则：{@code exist=false}、
 *       {@code transient}、{@code @TableField("col")}、驼峰转下划线）；{@code @TableField} 的 value 写成 SQL 表达式、
 *       且插入与更新策略都是 {@code NEVER} 的只读投影不是物理列，不参与比对（见 {@link #isReadOnlyProjection}）；</li>
 *   <li>迁移列：{@code CREATE TABLE} 的列定义、{@code ALTER TABLE … ADD COLUMN}、{@code RENAME COLUMN … TO}，
 *       注释里的不算，{@code format('%I.表')} 形式的动态 DDL 认得；</li>
 *   <li>快照列：结构快照里 {@code CREATE TABLE <占位符>.表 (…)} 的列定义。</li>
 * </ul>
 */
public final class EntityColumnAudit {

	private EntityColumnAudit() {
	}

	// ───────────────────────────── 实体列 ─────────────────────────────

	/**
	 * 扫 {@code basePackage} 下（当前类路径）全部 {@code @TableName} 实体，返回 table → (column → 来自哪个实体字段)。
	 * 测试源码里的桩实体、显式写了 schema 的表（不在租户 schema 里）不算。
	 */
	public static Map<String, Map<String, String>> entityColumns(String basePackage) {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(TableName.class));
		MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
		assistant.setCurrentNamespace("entity-column-audit");
		Map<String, Map<String, String>> out = new TreeMap<>();
		for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
			Class<?> type;
			try {
				type = Class.forName(bd.getBeanClassName());
			}
			catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
			if (isTestClass(type)) {
				continue;
			}
			TableInfo info = TableInfoHelper.initTableInfo(assistant, type);
			String table = unquote(info.getTableName());
			if (table.contains(".")) {
				continue;
			}
			Map<String, String> columns = out.computeIfAbsent(table, k -> new TreeMap<>());
			if (info.getKeyColumn() != null) {
				columns.put(unquote(info.getKeyColumn()), type.getSimpleName());
			}
			for (TableFieldInfo field : info.getFieldList()) {
				if (isReadOnlyProjection(field)) {
					continue;
				}
				columns.put(unquote(field.getColumn()), type.getSimpleName() + "#" + field.getProperty());
			}
		}
		return out;
	}

	/**
	 * 只读计算投影：{@code @TableField} 的 value 不是标识符而是 SQL 表达式（如 {@code case when … end}），
	 * 且插入、更新都不写它 —— 库里本来就没有这一列，也不该有迁移去建。
	 * 两个条件缺一不可：表达式列若允许写入，运行时 INSERT/UPDATE 会把表达式当列名，那才是要报出来的缺陷。
	 */
	static boolean isReadOnlyProjection(TableFieldInfo field) {
		return !SIMPLE_IDENTIFIER.matcher(unquote(field.getColumn())).matches()
				&& field.getInsertStrategy() == FieldStrategy.NEVER
				&& field.getUpdateStrategy() == FieldStrategy.NEVER;
	}

	private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

	private static boolean isTestClass(Class<?> type) {
		java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
		return source != null && source.getLocation() != null && source.getLocation().getPath().contains("/test-classes/");
	}

	private static String unquote(String identifier) {
		return identifier.replace("`", "").replace("\"", "").trim().toLowerCase(Locale.ROOT);
	}

	// ───────────────────────────── 迁移建出的列 ─────────────────────────────

	private static final Pattern CREATE_TABLE = Pattern.compile(
			"CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:[\\w%\"]+\\.)?\"?(\\w+)\"?\\s*\\(", Pattern.CASE_INSENSITIVE);

	private static final Pattern ALTER_TABLE = Pattern.compile(
			"ALTER\\s+TABLE\\s+(?:ONLY\\s+)?(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?(?:[\\w%\"]+\\.)?\"?(\\w+)\"?([^;]*)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern ADD_COLUMN = Pattern.compile(
			"\\bADD\\s+(?!CONSTRAINT\\b|PRIMARY\\b|UNIQUE\\b|CHECK\\b|FOREIGN\\b|EXCLUDE\\b)(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?\"?(\\w+)\"?",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern RENAME_COLUMN = Pattern.compile(
			"\\bRENAME\\s+(?:COLUMN\\s+)?\"?\\w+\"?\\s+TO\\s+\"?(\\w+)\"?", Pattern.CASE_INSENSITIVE);

	private static final Set<String> NOT_A_COLUMN = Set.of("constraint", "primary", "unique", "check", "foreign", "exclude",
			"like");

	/** 读目录下全部 {@code V{N}__*.sql}，返回 table → 建出的列。 */
	public static Map<String, Set<String>> migrationColumns(Path dir) {
		Map<String, Set<String>> out = new TreeMap<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql")).sorted().toList()) {
				parseMigration(Files.readString(f, StandardCharsets.UTF_8), out);
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out;
	}

	public static void parseMigration(String sql, Map<String, Set<String>> out) {
		String body = stripSqlComments(sql);
		Matcher create = CREATE_TABLE.matcher(body);
		while (create.find()) {
			String table = create.group(1).toLowerCase(Locale.ROOT);
			String defs = balanced(body, create.end() - 1);
			for (String def : splitTopLevel(defs)) {
				Matcher first = Pattern.compile("^\\s*\"?(\\w+)\"?").matcher(def);
				if (first.find() && !NOT_A_COLUMN.contains(first.group(1).toLowerCase(Locale.ROOT))) {
					out.computeIfAbsent(table, k -> new TreeSet<>()).add(first.group(1).toLowerCase(Locale.ROOT));
				}
			}
		}
		Matcher alter = ALTER_TABLE.matcher(body);
		while (alter.find()) {
			String table = alter.group(1).toLowerCase(Locale.ROOT);
			String actions = alter.group(2);
			for (Pattern p : List.of(ADD_COLUMN, RENAME_COLUMN)) {
				Matcher m = p.matcher(actions);
				while (m.find()) {
					out.computeIfAbsent(table, k -> new TreeSet<>()).add(m.group(1).toLowerCase(Locale.ROOT));
				}
			}
		}
	}

	/** 去掉 {@code --} 行注释与块注释（字符串字面量里的不动）。 */
	public static String stripSqlComments(String sql) {
		StringBuilder out = new StringBuilder(sql.length());
		boolean inString = false;
		for (int i = 0; i < sql.length(); i++) {
			char c = sql.charAt(i);
			if (inString) {
				out.append(c);
				if (c == '\'') {
					inString = false;
				}
				continue;
			}
			if (c == '\'') {
				inString = true;
				out.append(c);
			}
			else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
				while (i < sql.length() && sql.charAt(i) != '\n') {
					i++;
				}
				out.append('\n');
			}
			else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
				int end = sql.indexOf("*/", i + 2);
				i = end < 0 ? sql.length() : end + 1;
				out.append(' ');
			}
			else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** 从 {@code open} 处的左括号取到与之配对的右括号之间的内容。 */
	private static String balanced(String s, int open) {
		int depth = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(') {
				depth++;
			}
			else if (c == ')' && --depth == 0) {
				return s.substring(open + 1, i);
			}
		}
		return s.substring(open + 1);
	}

	private static List<String> splitTopLevel(String defs) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		int start = 0;
		for (int i = 0; i < defs.length(); i++) {
			char c = defs.charAt(i);
			if (c == '(') {
				depth++;
			}
			else if (c == ')') {
				depth--;
			}
			else if (c == ',' && depth == 0) {
				parts.add(defs.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(defs.substring(start));
		return parts;
	}

	// ───────────────────────────── 结构快照与存量清单 ─────────────────────────────

	/** 结构快照里 {@code CREATE TABLE <placeholder>.表 (…)} 的列定义，返回 table → 列。 */
	public static Map<String, Set<String>> snapshotColumns(String snapshot, String placeholder) {
		Map<String, Set<String>> out = new TreeMap<>();
		Matcher m = Pattern.compile("^CREATE TABLE " + Pattern.quote(placeholder) + "\\.(\\w+) \\(", Pattern.MULTILINE)
				.matcher(snapshot);
		while (m.find()) {
			Set<String> columns = new TreeSet<>();
			for (String def : splitTopLevel(balanced(snapshot, m.end() - 1))) {
				Matcher first = Pattern.compile("^\\s*\"?(\\w+)\"?").matcher(def);
				if (first.find() && !NOT_A_COLUMN.contains(first.group(1).toLowerCase(Locale.ROOT))) {
					columns.add(first.group(1).toLowerCase(Locale.ROOT));
				}
			}
			out.put(m.group(1).toLowerCase(Locale.ROOT), columns);
		}
		return out;
	}

	/** 存量清单：每行 {@code table.column}，{@code #} 开头是注释。 */
	public static Set<String> readColumnList(String content) {
		return content.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#"))
				.collect(Collectors.toCollection(TreeSet::new));
	}

	/**
	 * 实体映射了、却既不由任何迁移建出、也不在 {@code allowed}（存量清单 + 已登记的例外）里的列，格式 {@code table.column  ← 实体}。
	 */
	public static List<String> unversionedColumns(Map<String, Map<String, String>> entityColumns,
			Map<String, Set<String>> migrationColumns, Set<String> allowed) {
		List<String> missing = new ArrayList<>();
		entityColumns.forEach((table, columns) -> columns.forEach((column, entity) -> {
			if (!migrationColumns.getOrDefault(table, Set.of()).contains(column) && !allowed.contains(table + "." + column)) {
				missing.add(table + "." + column + "  ← " + entity);
			}
		}));
		return missing;
	}
}
