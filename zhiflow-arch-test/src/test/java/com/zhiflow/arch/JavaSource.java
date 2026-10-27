package com.zhiflow.arch;

/**
 * 源码级门禁共用的小工具。
 *
 * <p>本模块<b>刻意不依赖任何业务模块或 Spring</b>（见 pom 注释），一批规则因此是直接读
 * {@code .java} 文本判的。读文本的规则有一个共同的头号失效模式：
 * <b>把 javadoc 里「提到过」当成代码里「写了」</b>——
 * 而这类失效是<b>静默变绿</b>，不是报错。
 */
final class JavaSource {

	private JavaSource() {
	}

	/**
	 * 剥掉行注释与块注释（含 javadoc），<b>但保留字符串 / 字符 / 文本块字面量原样</b>。
	 *
	 * <h2>为什么必须是状态机而不是正则</h2>
	 * <p>{@code //} 与 {@code /*} 同样会出现在字符串字面量里（URL、路径、注释模板）。
	 * 用 {@code //.*$} 一刀切会把两份被比对的文件<b>各自</b>截断到同一个位置 ——
	 * 真实差异一起被切掉，判据于是静默变绿。
	 *
	 * <p>注释一律换成<b>一个空格</b>而不是删掉：删掉会把 {@code a}{@code /*x*&#47;}{@code b}
	 * 粘成 {@code ab}，制造出源码里根本没有的记号。行注释只吃到行尾、不吃换行，
	 * 所以「行首锚定」的规则（如 {@code ^@EnableXxx}）在剥完之后仍然成立。
	 */
	static String stripComments(String source) {
		StringBuilder out = new StringBuilder(source.length());
		int i = 0;
		int n = source.length();
		while (i < n) {
			char c = source.charAt(i);
			if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
				while (i < n && source.charAt(i) != '\n') {
					i++;
				}
				out.append(' ');
			}
			else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
				i += 2;
				while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
					i++;
				}
				i = Math.min(i + 2, n);
				out.append(' ');
			}
			else if (source.startsWith("\"\"\"", i)) {
				out.append("\"\"\"");
				i += 3;
				while (i < n && !source.startsWith("\"\"\"", i)) {
					out.append(source.charAt(i));
					i++;
				}
				out.append("\"\"\"");
				i = Math.min(i + 3, n);
			}
			else if (c == '"' || c == '\'') {
				out.append(c);
				i++;
				while (i < n && source.charAt(i) != c) {
					if (source.charAt(i) == '\\' && i + 1 < n) {
						out.append(source.charAt(i)).append(source.charAt(i + 1));
						i += 2;
						continue;
					}
					out.append(source.charAt(i));
					i++;
				}
				if (i < n) {
					out.append(c);
					i++;
				}
			}
			else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}
}
