package com.zhiflow.arch;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * [2026-10-01 CARD-15] 迁移遗留 SQL 静态扫描：MySQL 方言写法 + 两类危险形状。
 *
 * <h2>扫哪些 SQL</h2>
 * <p>不按「所有字符串字面量」扫（提示文字里也会出现 {@code IFNULL(} 这类词），只收四种来源：
 * <ol>
 * <li>MyBatis mapper XML 的 {@code <select|insert|update|delete|sql>} 语句体；</li>
 * <li>Mapper 接口上的 {@code @Select/@Update/@Insert/@Delete} 注解；</li>
 * <li>MyBatis-Plus 条件构造器的原生 SQL 片段：{@code .apply/.last/.inSql/.notInSql/.exists/.notExists/.having/.setSql/.select}；</li>
 * <li>引用了 {@code JdbcTemplate/JdbcClient} 的 Java 文件里、长得像 SQL 的字面量。</li>
 * </ol>
 *
 * <h2>判据的两个失效模式（已在 {@code SqlLegacyScannerTest} 里各放一个对照样本）</h2>
 * <ul>
 * <li><b>注释里「提到过」被当成「写了」</b>：XML 注释、SQL {@code --} 注释、Java 注释都先剥掉。
 * 2026-10-01 粗扫时 mapper XML 里的 9 个反引号全部在注释里——不剥就是 9 条假阳性。</li>
 * <li><b>CH 语句被当成 PG</b>：见上，按来源收而不是按字面量收。</li>
 * </ul>
 *
 * <p>本类刻意不依赖 MyBatis / Spring（与本模块其余门禁同理）：XML 用 JDK 自带的 DOM 解析，
 * 并关闭外部 DTD 加载（mapper 头上的 {@code mybatis-3-mapper.dtd} 是 http 地址，离线时会卡住）。
 */
final class SqlLegacyScanner {

	private SqlLegacyScanner() {
	}

	/** 一条命中。{@code anchor}：XML 为语句 id；Java 为空（Java 侧按「规则 + 文件」计数）。 */
	record Finding(String rule, String file, String anchor, int line, String snippet) {

		String key() {
			return rule + "\t" + file + (anchor.isEmpty() ? "" : "#" + anchor);
		}
	}

	record DialectRule(String id, Pattern pattern, String hint) {
	}

	private static final int FLAGS = Pattern.CASE_INSENSITIVE;

	/** MySQL 方言规则。命中即「在 PG 上必然报错或语义不同」，不存在「PG 也接受」的写法。 */
	static final List<DialectRule> DIALECT_RULES = List.of(
			new DialectRule("MYSQL_IFNULL", Pattern.compile("\\bIFNULL\\s*\\(", FLAGS),
					"PG 没有 IFNULL，用 COALESCE"),
			new DialectRule("MYSQL_DATE_FUNC",
					Pattern.compile("\\b(DATE_FORMAT|STR_TO_DATE|DATE_SUB|CURDATE|CURTIME|UNIX_TIMESTAMP|FROM_UNIXTIME"
							+ "|TIMESTAMPDIFF|DATEDIFF|SYSDATE)\\s*\\(", FLAGS),
					"PG 用 to_char / to_timestamp / extract(epoch ...) / now() / 区间运算"),
			new DialectRule("MYSQL_INTERVAL",
					Pattern.compile("\\bINTERVAL\\s+(\\d+|#\\{[^}]*}|\\$\\{[^}]*}|\\?)\\s+"
							+ "(SECOND|MINUTE|HOUR|DAY|WEEK|MONTH|QUARTER|YEAR)\\b", FLAGS),
					"PG 写 INTERVAL '1 day'，参数化用 make_interval(days => #{n})"),
			new DialectRule("MYSQL_FUNC",
					Pattern.compile("\\b(GROUP_CONCAT|FIND_IN_SET|SUBSTRING_INDEX|LOCATE|INSTR|LAST_INSERT_ID|FOUND_ROWS"
							+ "|JSON_EXTRACT|JSON_CONTAINS|JSON_UNQUOTE|ISNULL)\\s*\\(", FLAGS),
					"PG 对应：string_agg / = ANY(string_to_array()) / split_part / strpos / RETURNING / jsonb 运算符"),
			new DialectRule("MYSQL_IF_FUNC", Pattern.compile("(?<![\\w.])IF\\s*\\(", FLAGS),
					"PG 没有 IF() 函数，用 CASE WHEN ... THEN ... ELSE ... END"),
			new DialectRule("MYSQL_BACKTICK", Pattern.compile("`"),
					"PG 的标识符引号是双引号；反引号是语法错误"),
			new DialectRule("MYSQL_LIMIT_COMMA",
					Pattern.compile("\\bLIMIT\\s+(\\d+|#\\{[^}]*}|\\$\\{[^}]*}|\\?)\\s*,\\s*(\\d+|#\\{[^}]*}|\\$\\{[^}]*}|\\?)",
							FLAGS),
					"PG 不支持 LIMIT a,b，写 LIMIT b OFFSET a"),
			new DialectRule("MYSQL_UPSERT",
					Pattern.compile("\\bON\\s+DUPLICATE\\s+KEY\\b|\\bINSERT\\s+IGNORE\\b|\\bREPLACE\\s+INTO\\b", FLAGS),
					"PG 用 INSERT ... ON CONFLICT (...) DO UPDATE / DO NOTHING"),
			new DialectRule("MYSQL_REGEXP", Pattern.compile("\\b(REGEXP|RLIKE)\\b", FLAGS),
					"PG 用 ~ / ~*"),
			new DialectRule("MYSQL_CAST",
					Pattern.compile("\\bCAST\\s*\\([^()]*?\\bAS\\s+(SIGNED|UNSIGNED|DATETIME)\\b"
							+ "|\\bCONVERT\\s*\\([^()]*,\\s*(CHAR|SIGNED|UNSIGNED|DATETIME|DECIMAL)\\b", FLAGS),
					"PG 用 ::bigint / ::timestamp / ::text"),
			new DialectRule("MYSQL_ORDER_FIELD", Pattern.compile("\\bORDER\\s+BY\\s+FIELD\\s*\\(", FLAGS),
					"PG 用 array_position(ARRAY[...], col) 或 CASE"),
			new DialectRule("TENANT_SCHEMA_HARDCODED",
					Pattern.compile("\\bzhiflow_(mall|im)(_\\d+)?\\s*\\.\\s*[a-z_]", FLAGS),
					"租户 schema 写死在 SQL 里：多租户隔离靠 search_path，写死等于固定读某一个租户（或 base）的表"));

	/** XML 专属：双引号字符串字面量（MySQL 当字符串，PG 当列名 → column "x" does not exist）。 */
	static final DialectRule XML_DQ_LITERAL = new DialectRule("MYSQL_DQ_LITERAL",
			Pattern.compile("(=|<>|!=)\\s*\"[^\"\\n]*\"(?!\\s*\\.)"),
			"PG 的字符串字面量只能用单引号；双引号是标识符");

	static final String SELECT_ONE_ALL_OPTIONAL = "SELECT_ONE_ALL_OPTIONAL";

	static final String QUERY_BY_ENTITY = "QUERY_BY_ENTITY";

	// ---------------------------------------------------------------- 入口

	private static final Set<String> SKIP_DIRS = Set.of("target", ".claude", "node_modules", ".git", ".idea");

	/** 扫整个仓库：根目录下 {@code zhiflow-*} 模块的 {@code src/main}。 */
	static List<Finding> scan(Path repoRoot) {
		List<Path> files = new ArrayList<>();
		try (Stream<Path> roots = Files.list(repoRoot)) {
			for (Path module : roots.filter(p -> p.getFileName().toString().startsWith("zhiflow-"))
					.filter(Files::isDirectory)
					.sorted()
					.toList()) {
				// 遍历时直接剪掉构建产物与副本目录，而不是走进去再过滤：target/ 下的文件在测试运行期间
				// 正被 surefire 改写，走进去会撞上「刚列出来就被删掉」的文件（2026-10-01 实测 NoSuchFileException）
				Files.walkFileTree(module, new SimpleFileVisitor<>() {
					@Override
					public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
						String name = dir.getFileName().toString();
						return SKIP_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
						String s = file.toString().replace('\\', '/');
						if (s.contains("/src/main/") && (s.endsWith(".xml") || s.endsWith(".java"))) {
							files.add(file);
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException exc) {
						return FileVisitResult.CONTINUE;
					}
				});
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		files.sort(null);

		Map<String, Path> javaByFqcnPath = new HashMap<>();
		for (Path p : files) {
			String s = p.toString().replace('\\', '/');
			int i = s.indexOf("/src/main/java/");
			if (s.endsWith(".java") && i >= 0) {
				javaByFqcnPath.putIfAbsent(s.substring(i + "/src/main/java/".length()), p);
			}
		}
		Function<String, String> javaSourceByFqcn = fqcn -> {
			Path p = javaByFqcnPath.get(fqcn.replace('.', '/') + ".java");
			return p == null ? null : read(p);
		};

		List<Finding> out = new ArrayList<>();
		for (Path p : files) {
			String rel = repoRoot.relativize(p).toString().replace('\\', '/');
			String text = read(p);
			if (rel.endsWith(".xml")) {
				if (text.contains("<mapper")) {
					out.addAll(scanMapperXml(rel, text, javaSourceByFqcn));
				}
			}
			else {
				out.addAll(scanJava(rel, text));
			}
		}
		return out;
	}

	private static String read(Path p) {
		try {
			return Files.readString(p, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---------------------------------------------------------------- mapper XML

	private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete", "sql");

	static List<Finding> scanMapperXml(String rel, String xml, Function<String, String> javaSourceByFqcn) {
		Document doc = parseXml(xml);
		Element root = doc.getDocumentElement();
		if (!"mapper".equals(root.getTagName())) {
			return List.of();
		}
		String namespace = root.getAttribute("namespace");
		String mapperSource = namespace.isEmpty() ? null : javaSourceByFqcn.apply(namespace);
		String mapperBlanked = mapperSource == null ? null : blankJavaComments(mapperSource);

		List<Finding> out = new ArrayList<>();
		NodeList children = root.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			if (!(children.item(i) instanceof Element stmt) || !STATEMENT_TAGS.contains(stmt.getTagName())) {
				continue;
			}
			String id = stmt.getAttribute("id");
			int line = statementLine(xml, stmt.getTagName(), id);
			String sql = stripSqlComments(stmt.getTextContent());
			for (DialectRule rule : DIALECT_RULES) {
				addMatches(out, rule, sql, rel, id, line);
			}
			addMatches(out, XML_DQ_LITERAL, sql, rel, id, line);
			if ("select".equals(stmt.getTagName()) && mapperBlanked != null
					&& isSelectOneAllOptional(stmt, sql, mapperBlanked, id)) {
				out.add(new Finding(SELECT_ONE_ALL_OPTIONAL, rel, id, line, oneLine(sql)));
			}
		}
		return out;
	}

	private static Document parseXml(String xml) {
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setValidating(false);
			f.setNamespaceAware(false);
			f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			f.setFeature("http://xml.org/sax/features/external-general-entities", false);
			f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			DocumentBuilder b = f.newDocumentBuilder();
			b.setErrorHandler(null);
			return b.parse(new InputSource(new StringReader(xml)));
		}
		catch (Exception e) {
			throw new IllegalStateException("mapper XML 解析失败：" + e.getMessage(), e);
		}
	}

	private static int statementLine(String xml, String tag, String id) {
		Matcher m = Pattern.compile("<" + tag + "\\b[^>]*\\bid\\s*=\\s*\"" + Pattern.quote(id) + "\"").matcher(xml);
		return m.find() ? lineOf(xml, m.start()) : 0;
	}

	/**
	 * {@code <select>} 返回单值（不是集合/分页），且 WHERE 条件全部可空、没有 LIMIT，
	 * 也不是「首列聚合且无 GROUP BY」——入参全空时退化成「整个租户的表」，
	 * {@code selectOne} 抛 {@code TooManyResultsException} → 500。
	 * 判据与 database-guidelines.md「selectOne + 全可空的 &lt;if&gt;」一节一致。
	 */
	static boolean isSelectOneAllOptional(Element select, String sql, String mapperBlanked, String id) {
		String upper = sql.toUpperCase(Locale.ROOT);
		// LIMIT / GROUP BY 只看最外层：子查询里的 LIMIT 限不住外层行数，子查询里的 GROUP BY
		// 也不会让外层的 COUNT(*) 变成多行（LargeScreenStatMapper.countNewVisitors 就是这个形状）
		String top = topLevel(upper);
		if (top.matches("(?s).*\\bLIMIT\\b.*") || top.contains("FETCH FIRST")) {
			return false;
		}
		if (upper.matches("(?s)\\s*SELECT\\s+(COUNT|SUM|MAX|MIN|AVG|COALESCE\\s*\\(\\s*(SUM|COUNT|MAX|MIN|AVG))\\s*\\(.*")
				&& !top.matches("(?s).*\\bGROUP\\s+BY\\b.*")) {
			return false;
		}
		String returnType = mapperReturnType(mapperBlanked, id);
		if (returnType == null || !isSingleRow(returnType)) {
			return false;
		}
		Boolean whereOptional = whereAllOptional(select);
		return Boolean.TRUE.equals(whereOptional);
	}

	/** 去掉所有括号内的内容（含子查询、函数实参），只留最外层的 SQL 骨架。 */
	static String topLevel(String sql) {
		StringBuilder out = new StringBuilder(sql.length());
		int depth = 0;
		for (int i = 0; i < sql.length(); i++) {
			char c = sql.charAt(i);
			if (c == '(') {
				depth++;
			}
			else if (c == ')') {
				depth = Math.max(0, depth - 1);
			}
			else if (depth == 0) {
				out.append(c);
			}
		}
		return out.toString();
	}

	/**
	 * @return true = 存在 WHERE 且条件全部可空；false = 有硬条件；null = 没有 WHERE（不判，见类注释）
	 */
	private static Boolean whereAllOptional(Element select) {
		List<Element> whereLike = new ArrayList<>();
		collectWhereLike(select, whereLike);
		if (!whereLike.isEmpty()) {
			for (Element w : whereLike) {
				if (!onlyOptionalChildren(w)) {
					return false;
				}
			}
			return true;
		}
		// 「WHERE 1=1 <if>...</if>」：select 直接文本里 WHERE 之后只剩恒真式
		StringBuilder direct = new StringBuilder();
		boolean hasIf = false;
		for (Node n = select.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
				direct.append(n.getNodeValue());
			}
			else if (n instanceof Element e) {
				if ("if".equals(e.getTagName())) {
					hasIf = true;
				}
				direct.append(' ');
			}
		}
		String d = stripSqlComments(direct.toString());
		Matcher m = Pattern.compile("\\bWHERE\\b", FLAGS).matcher(d);
		int last = -1;
		while (m.find()) {
			last = m.end();
		}
		if (last < 0) {
			return null;
		}
		String tail = d.substring(last)
				.split("(?i)\\b(ORDER\\s+BY|GROUP\\s+BY|LIMIT|OFFSET|FOR\\s+UPDATE|HAVING)\\b", 2)[0]
				.replaceAll("(?i)1\\s*=\\s*1|\\bTRUE\\b|\\bAND\\b", " ")
				.trim();
		return hasIf && tail.isEmpty();
	}

	private static void collectWhereLike(Element e, List<Element> out) {
		for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element c) {
				boolean whereTrim = "trim".equals(c.getTagName())
						&& c.getAttribute("prefix").trim().equalsIgnoreCase("where");
				if ("where".equals(c.getTagName()) || whereTrim) {
					out.add(c);
				}
				else {
					collectWhereLike(c, out);
				}
			}
		}
	}

	/** where 元素里没有任何直接文本（硬条件），子元素只有 if / 无 otherwise 的 choose / bind。 */
	private static boolean onlyOptionalChildren(Element where) {
		for (Node n = where.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
				if (!stripSqlComments(n.getNodeValue()).isBlank()) {
					return false;
				}
			}
			else if (n instanceof Element c) {
				switch (c.getTagName()) {
					case "if", "bind" -> {
					}
					case "choose" -> {
						if (c.getElementsByTagName("otherwise").getLength() > 0) {
							return false;
						}
					}
					default -> {
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * 在 mapper 接口源码（已剥注释）里找方法 {@code id} 的声明返回类型原文。
	 * 从方法名往回扫：跳空白 → 若是 {@code >} 则配平到 {@code <} → 再取标识符（含包名与数组）。
	 */
	static String mapperReturnType(String blanked, String id) {
		Matcher m = Pattern.compile("(?<![\\w.])" + Pattern.quote(id) + "\\s*\\(").matcher(blanked);
		while (m.find()) {
			int i = m.start() - 1;
			while (i >= 0 && Character.isWhitespace(blanked.charAt(i))) {
				i--;
			}
			int end = i + 1;
			if (i >= 0 && blanked.charAt(i) == ']') {
				while (i >= 0 && blanked.charAt(i) != '[') {
					i--;
				}
				i--;
				while (i >= 0 && Character.isWhitespace(blanked.charAt(i))) {
					i--;
				}
			}
			if (i >= 0 && blanked.charAt(i) == '>') {
				int depth = 0;
				for (; i >= 0; i--) {
					char c = blanked.charAt(i);
					if (c == '>') {
						depth++;
					}
					else if (c == '<' && --depth == 0) {
						i--;
						break;
					}
				}
				while (i >= 0 && Character.isWhitespace(blanked.charAt(i))) {
					i--;
				}
			}
			int j = i;
			while (j >= 0 && (Character.isJavaIdentifierPart(blanked.charAt(j)) || blanked.charAt(j) == '.')) {
				j--;
			}
			if (j == i) {
				continue; // 是调用不是声明（前面是 . 或 ( 等）
			}
			String type = blanked.substring(j + 1, end).replaceAll("\\s+", "");
			String head = type.replaceAll("<.*", "");
			if (Set.of("return", "new", "throw", "else").contains(head)) {
				continue;
			}
			return type;
		}
		return null;
	}

	private static final Set<String> MULTI_ROW_TYPES = Set.of("List", "Collection", "Set", "IPage", "Page",
			"Iterable", "Stream", "Cursor", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet");

	static boolean isSingleRow(String type) {
		if (type.endsWith("[]") || "void".equals(type)) {
			return false;
		}
		String base = type.replaceAll("<.*", "");
		base = base.substring(base.lastIndexOf('.') + 1);
		return !MULTI_ROW_TYPES.contains(base);
	}

	// ---------------------------------------------------------------- Java

	private static final Pattern ANNOTATION_SQL = Pattern.compile("@(Select|Update|Insert|Delete)\\s*\\(");

	private static final Pattern WRAPPER_RAW = Pattern
			.compile("\\.\\s*(apply|last|inSql|notInSql|exists|notExists|having|setSql|select)\\s*\\(");

	private static final Pattern LOOKS_LIKE_SQL = Pattern.compile(
			"\\b(SELECT\\s+.+\\bFROM|INSERT\\s+INTO|UPDATE\\s+\\S+\\s+SET|DELETE\\s+FROM|WITH\\s+\\w+\\s+AS)\\b",
			FLAGS | Pattern.DOTALL);

	private static final Pattern ENTITY_WRAPPER = Pattern.compile(
			"Wrappers\\s*\\.\\s*(?:<[^>]*>\\s*)?(query|lambdaQuery|update|lambdaUpdate)\\s*\\(\\s*([A-Za-z_][\\w.]*)\\s*\\)"
					+ "|new\\s+(?:Lambda)?(?:Query|Update)Wrapper\\s*<[^>]*>\\s*\\(\\s*([A-Za-z_][\\w.]*)\\s*\\)");

	static List<Finding> scanJava(String rel, String src) {
		String blanked = blankJavaComments(src);
		List<Finding> out = new ArrayList<>();

		Matcher a = ANNOTATION_SQL.matcher(blanked);
		while (a.find()) {
			int close = matchParen(blanked, a.end() - 1);
			String sql = cleanJavaSql(String.join(" ", literalsIn(src, a.end(), close)));
			int line = lineOf(src, a.start());
			for (DialectRule rule : DIALECT_RULES) {
				addMatches(out, rule, sql, rel, "", line);
			}
		}

		Matcher w = WRAPPER_RAW.matcher(blanked);
		while (w.find()) {
			int close = matchParen(blanked, w.end() - 1);
			List<String> lits = literalsIn(src, w.end(), close);
			if (lits.isEmpty()) {
				continue;
			}
			String sql = cleanJavaSql(String.join(" ", lits));
			int line = lineOf(src, w.start());
			for (DialectRule rule : DIALECT_RULES) {
				addMatches(out, rule, sql, rel, "", line);
			}
		}

		if (blanked.contains("JdbcTemplate") || blanked.contains("JdbcClient")) {
			for (Literal lit : mergedLiterals(src, blanked)) {
				String sql = cleanJavaSql(lit.text());
				if (!LOOKS_LIKE_SQL.matcher(sql).find()) {
					continue;
				}
				for (DialectRule rule : DIALECT_RULES) {
					addMatches(out, rule, sql, rel, "", lineOf(src, lit.start()));
				}
			}
		}

		Matcher e = ENTITY_WRAPPER.matcher(blanked);
		while (e.find()) {
			String arg = e.group(2) != null ? e.group(2) : e.group(3);
			if (arg.endsWith(".class") || arg.toLowerCase(Locale.ROOT).matches(".*(class|clazz)$")) {
				continue;
			}
			out.add(new Finding(QUERY_BY_ENTITY, rel, "", lineOf(src, e.start()), e.group()));
		}
		return out;
	}

	private static String cleanJavaSql(String sql) {
		String s = sql;
		if (s.contains("<script>")) {
			s = s.replaceAll("<[^>]+>", " ")
					.replace("&lt;", "<")
					.replace("&gt;", ">")
					.replace("&quot;", "\"")
					.replace("&amp;", "&");
		}
		return stripSqlComments(s);
	}

	record Literal(int start, String text) {
	}

	/** 区间 [from, to) 内全部字符串 / 文本块字面量的内容（已反转义），按出现顺序。 */
	static List<String> literalsIn(String src, int from, int to) {
		List<String> out = new ArrayList<>();
		for (Literal l : literals(src)) {
			if (l.start() >= from && l.start() < to) {
				out.add(l.text());
			}
		}
		return out;
	}

	/** 相邻字面量之间只隔着 {@code +} 与空白时拼成一条（{@code "SELECT a " + "FROM t"}）。 */
	private static List<Literal> mergedLiterals(String src, String blanked) {
		List<Literal> all = literals(src);
		List<Literal> out = new ArrayList<>();
		Literal cur = null;
		int curEnd = -1;
		for (Literal l : all) {
			if (cur != null && blanked.substring(curEnd, l.start()).matches("\\s*\\+\\s*")) {
				cur = new Literal(cur.start(), cur.text() + l.text());
			}
			else {
				if (cur != null) {
					out.add(cur);
				}
				cur = l;
			}
			curEnd = endOfLiteral(src, l.start());
		}
		if (cur != null) {
			out.add(cur);
		}
		return out;
	}

	private static int endOfLiteral(String src, int start) {
		if (src.startsWith("\"\"\"", start)) {
			int e = src.indexOf("\"\"\"", start + 3);
			return e < 0 ? src.length() : e + 3;
		}
		int i = start + 1;
		while (i < src.length() && src.charAt(i) != '"') {
			i += src.charAt(i) == '\\' ? 2 : 1;
		}
		return Math.min(i + 1, src.length());
	}

	/** 全文件的字符串字面量（跳过注释与字符字面量）。 */
	static List<Literal> literals(String src) {
		List<Literal> out = new ArrayList<>();
		int i = 0;
		int n = src.length();
		while (i < n) {
			char c = src.charAt(i);
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
				while (i < n && src.charAt(i) != '\n') {
					i++;
				}
			}
			else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
				int e = src.indexOf("*/", i + 2);
				i = e < 0 ? n : e + 2;
			}
			else if (src.startsWith("\"\"\"", i)) {
				int e = src.indexOf("\"\"\"", i + 3);
				int end = e < 0 ? n : e;
				int bodyStart = src.indexOf('\n', i + 3);
				bodyStart = bodyStart < 0 || bodyStart > end ? i + 3 : bodyStart + 1;
				out.add(new Literal(i, src.substring(bodyStart, end)));
				i = e < 0 ? n : e + 3;
			}
			else if (c == '"') {
				StringBuilder sb = new StringBuilder();
				int start = i;
				i++;
				while (i < n && src.charAt(i) != '"' && src.charAt(i) != '\n') {
					char d = src.charAt(i);
					if (d == '\\' && i + 1 < n) {
						char x = src.charAt(i + 1);
						sb.append(switch (x) {
							case 'n' -> '\n';
							case 't' -> '\t';
							default -> x;
						});
						i += 2;
						continue;
					}
					sb.append(d);
					i++;
				}
				i++;
				out.add(new Literal(start, sb.toString()));
			}
			else if (c == '\'') {
				i++;
				while (i < n && src.charAt(i) != '\'') {
					i += src.charAt(i) == '\\' ? 2 : 1;
				}
				i++;
			}
			else {
				i++;
			}
		}
		return out;
	}

	/** 把注释替换成等长空白（保留换行），字面量原样 —— 偏移与行号都不变。 */
	static String blankJavaComments(String src) {
		StringBuilder out = new StringBuilder(src);
		int i = 0;
		int n = src.length();
		while (i < n) {
			char c = src.charAt(i);
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
				while (i < n && src.charAt(i) != '\n') {
					out.setCharAt(i, ' ');
					i++;
				}
			}
			else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
				int e = src.indexOf("*/", i + 2);
				int end = e < 0 ? n : e + 2;
				for (int k = i; k < end; k++) {
					if (src.charAt(k) != '\n') {
						out.setCharAt(k, ' ');
					}
				}
				i = end;
			}
			else if (src.startsWith("\"\"\"", i)) {
				int e = src.indexOf("\"\"\"", i + 3);
				i = e < 0 ? n : e + 3;
			}
			else if (c == '"' || c == '\'') {
				i++;
				while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
					i += src.charAt(i) == '\\' ? 2 : 1;
				}
				i++;
			}
			else {
				i++;
			}
		}
		return out.toString();
	}

	/** 从 {@code open}（指向 '('）配平到对应的 ')'，跳过字面量。返回 ')' 的下标。 */
	static int matchParen(String blanked, int open) {
		int depth = 0;
		int i = open;
		int n = blanked.length();
		while (i < n) {
			char c = blanked.charAt(i);
			if (blanked.startsWith("\"\"\"", i)) {
				int e = blanked.indexOf("\"\"\"", i + 3);
				i = e < 0 ? n : e + 3;
				continue;
			}
			if (c == '"' || c == '\'') {
				i++;
				while (i < n && blanked.charAt(i) != c && blanked.charAt(i) != '\n') {
					i += blanked.charAt(i) == '\\' ? 2 : 1;
				}
				i++;
				continue;
			}
			if (c == '(') {
				depth++;
			}
			else if (c == ')' && --depth == 0) {
				return i;
			}
			i++;
		}
		return n;
	}

	// ---------------------------------------------------------------- 共用

	/** 剥 SQL 注释：{@code -- ...} 到行尾、{@code /* ... *&#47;}。单引号字符串内的不动。 */
	static String stripSqlComments(String sql) {
		StringBuilder out = new StringBuilder(sql.length());
		int i = 0;
		int n = sql.length();
		while (i < n) {
			char c = sql.charAt(i);
			if (c == '\'') {
				int e = i + 1;
				while (e < n) {
					if (sql.charAt(e) == '\'') {
						if (e + 1 < n && sql.charAt(e + 1) == '\'') {
							e += 2;
							continue;
						}
						break;
					}
					e++;
				}
				e = Math.min(e + 1, n);
				out.append(sql, i, e);
				i = e;
			}
			else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
				while (i < n && sql.charAt(i) != '\n') {
					i++;
				}
				out.append(' ');
			}
			else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
				int e = sql.indexOf("*/", i + 2);
				i = e < 0 ? n : e + 2;
				out.append(' ');
			}
			else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	private static void addMatches(List<Finding> out, DialectRule rule, String sql, String rel, String anchor,
			int line) {
		// 单引号字符串里的内容（如 WHERE note = 'IFNULL(x)'、正则 '^a`b'）不是 SQL 写法，先抹掉再匹配
		Matcher m = rule.pattern().matcher(maskSqlStrings(sql));
		while (m.find()) {
			int from = Math.max(0, m.start() - 40);
			int to = Math.min(sql.length(), m.end() + 40);
			out.add(new Finding(rule.id(), rel, anchor, line, oneLine(sql.substring(from, to))));
		}
	}

	/** 把单引号字符串的内容换成等长空格（保留引号本身与偏移），供方言规则匹配用。 */
	static String maskSqlStrings(String sql) {
		StringBuilder out = new StringBuilder(sql);
		int i = 0;
		int n = sql.length();
		while (i < n) {
			if (sql.charAt(i) == '\'') {
				int e = i + 1;
				while (e < n) {
					if (sql.charAt(e) == '\'') {
						if (e + 1 < n && sql.charAt(e + 1) == '\'') {
							e += 2;
							continue;
						}
						break;
					}
					e++;
				}
				for (int k = i + 1; k < Math.min(e, n); k++) {
					if (out.charAt(k) != '\n') {
						out.setCharAt(k, ' ');
					}
				}
				i = e + 1;
			}
			else {
				i++;
			}
		}
		return out.toString();
	}

	private static String oneLine(String s) {
		String t = s.replaceAll("\\s+", " ").trim();
		return t.length() > 160 ? t.substring(0, 160) + "…" : t;
	}

	static int lineOf(String text, int offset) {
		int line = 1;
		for (int i = 0; i < offset && i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}
}
