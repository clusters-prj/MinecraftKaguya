package com.clustersprj.fjeconomy.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * config.yml を「行の並び」として編集するための最小限のYAMLエディタです。
 * <p>
 * snakeyaml で読み込んで {@code Yaml.dump()} で書き戻すと、
 * config.yml に書かれている説明コメントがすべて失われてしまいます。
 * このクラスは元のテキストを保ったままキーの追加・削除・改名・値の変更だけを行うため、
 * 管理者が読むコメントや並び順、手書きの追記が維持されます。
 * </p>
 * <p>
 * 完全なYAMLパーサではありません。config.yml で実際に使っている
 * 「2スペース字下げのマッピング」「スカラー値」「インラインの空リスト」を対象とし、
 * 複雑なアンカーやフロー記法は扱いません。値の解釈は従来どおり snakeyaml が行い、
 * このクラスは構造の編集のみを担当します。
 * </p>
 */
public final class YamlLines {

    /**
     * キー行にマッチする正規表現。
     * {@code [^:]*} は貪欲でもコロンを越えないため、
     * {@code url: "jdbc:mariadb://..."} のように値にコロンを含む行でも
     * 最初のコロンでキーと値が分割される。
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^(\\s*)([^\\s#:][^:]*):(.*)$");

    /** 既定の字下げ幅（子キーが1つも無いセクションに追記するときに使う）。 */
    private static final int DEFAULT_INDENT_WIDTH = 2;

    private final List<String> lines;

    public YamlLines(List<String> lines) {
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 現在の内容をファイルへ書ける1つの文字列にします。
     *
     * @return 末尾に改行を含むYAMLテキスト
     */
    public String toText() {
        return String.join("\n", lines) + "\n";
    }

    // ==========================================
    // 行の種類判定
    // ==========================================

    private static Matcher matchKey(String line) {
        Matcher matcher = KEY_PATTERN.matcher(line);
        return matcher.matches() ? matcher : null;
    }

    private static boolean isBlank(String line) {
        return line.trim().isEmpty();
    }

    private static boolean isComment(String line) {
        return line.trim().startsWith("#");
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    // ==========================================
    // 位置の探索
    // ==========================================

    /**
     * ドット区切りのパスに対応するキー行の位置を探します。
     *
     * @param path 例: {@code "build_reward.interval_hours"}
     * @return 行インデックス。見つからない場合は -1
     */
    public int indexOf(String path) {
        int searchStart = 0;
        int searchEnd = lines.size();
        int expectedIndent = 0;
        int found = -1;

        for (String segment : path.split("\\.")) {
            found = -1;
            for (int i = searchStart; i < searchEnd; i++) {
                Matcher matcher = matchKey(lines.get(i));
                if (matcher == null || matcher.group(1).length() != expectedIndent) {
                    continue;
                }
                if (matcher.group(2).trim().equals(segment)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return -1;
            }
            searchStart = found + 1;
            searchEnd = blockEndExclusive(found);
            expectedIndent = childIndentOf(found);
        }

        return found;
    }

    /**
     * 指定パスのキーが存在するかを返します。
     *
     * @param path ドット区切りのパス
     * @return 存在すれば true
     */
    public boolean has(String path) {
        return indexOf(path) >= 0;
    }

    /**
     * キー行の配下ブロックが終わる位置（この位置は含まない）を返します。
     * <p>
     * ブロック末尾に続く空行やコメント行は、次のキーに付随する説明とみなして
     * ブロックから除外します。
     * </p>
     */
    private int blockEndExclusive(int keyIndex) {
        int keyIndent = indentOf(lines.get(keyIndex));
        int end = lines.size();

        for (int i = keyIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isBlank(line) || isComment(line)) {
                continue;
            }
            if (indentOf(line) <= keyIndent) {
                end = i;
                break;
            }
        }

        while (end > keyIndex + 1 && (isBlank(lines.get(end - 1)) || isComment(lines.get(end - 1)))) {
            end--;
        }
        return end;
    }

    /** 配下の子キーが使っている字下げ幅を返します（子が無ければ既定値）。 */
    private int childIndentOf(int keyIndex) {
        int keyIndent = indentOf(lines.get(keyIndex));
        int end = blockEndExclusive(keyIndex);

        for (int i = keyIndex + 1; i < end; i++) {
            Matcher matcher = matchKey(lines.get(i));
            if (matcher != null) {
                return matcher.group(1).length();
            }
        }
        return keyIndent + DEFAULT_INDENT_WIDTH;
    }

    /** キー行の直上に連続しているコメント行を含めた開始位置を返します。 */
    private int leadingCommentStart(int keyIndex) {
        int start = keyIndex;
        while (start > 0 && isComment(lines.get(start - 1))) {
            start--;
        }
        return start;
    }

    // ==========================================
    // 構造の読み取り
    // ==========================================

    /**
     * 指定セクションの直下にある子キー名を、ファイル内の並び順で返します。
     *
     * @param path セクションのパス。ルートを指す場合は空文字
     * @return 子キー名のリスト（セクションが無ければ空リスト）
     */
    public List<String> childKeys(String path) {
        int searchStart;
        int searchEnd;
        int expectedIndent;

        if (path.isEmpty()) {
            searchStart = 0;
            searchEnd = lines.size();
            expectedIndent = 0;
        } else {
            int index = indexOf(path);
            if (index < 0) {
                return Collections.emptyList();
            }
            searchStart = index + 1;
            searchEnd = blockEndExclusive(index);
            expectedIndent = childIndentOf(index);
        }

        List<String> keys = new ArrayList<>();
        for (int i = searchStart; i < searchEnd; i++) {
            Matcher matcher = matchKey(lines.get(i));
            if (matcher != null && matcher.group(1).length() == expectedIndent) {
                keys.add(matcher.group(2).trim());
            }
        }
        return keys;
    }

    /**
     * 指定パスが子キーを持つセクションかどうかを返します。
     *
     * @param path ドット区切りのパス
     * @return 子キーが1つ以上あれば true
     */
    public boolean isSection(String path) {
        return !childKeys(path).isEmpty();
    }

    /**
     * キーとその説明コメント・配下ブロックをまとめて取り出します。
     *
     * @param path ドット区切りのパス
     * @return 抜き出した行のリスト。見つからない場合は空リスト
     */
    public List<String> extractBlock(String path) {
        int index = indexOf(path);
        if (index < 0) {
            return Collections.emptyList();
        }
        return new ArrayList<>(lines.subList(leadingCommentStart(index), blockEndExclusive(index)));
    }

    /**
     * 指定パスのキー行の字下げ幅を返します。
     *
     * @param path ドット区切りのパス
     * @return 字下げ幅。見つからない場合は -1
     */
    public int indentAt(String path) {
        int index = indexOf(path);
        return index < 0 ? -1 : indentOf(lines.get(index));
    }

    /**
     * 指定セクションの子キーに使うべき字下げ幅を返します。
     *
     * @param path セクションのパス。ルートを指す場合は空文字
     * @return 字下げ幅
     */
    public int childIndent(String path) {
        if (path.isEmpty()) {
            return 0;
        }
        int index = indexOf(path);
        return index < 0 ? DEFAULT_INDENT_WIDTH : childIndentOf(index);
    }

    // ==========================================
    // 編集
    // ==========================================

    /**
     * スカラー値を書き換えます。行末のインラインコメントは維持します。
     *
     * @param path ドット区切りのパス
     * @param rawValue YAMLとしてそのまま書き込む値（文字列を引用符で囲む必要がある場合は呼び出し側で付ける）
     * @return 書き換えた場合は true、キーが無ければ false
     */
    public boolean setScalar(String path, String rawValue) {
        int index = indexOf(path);
        if (index < 0) {
            return false;
        }

        Matcher matcher = matchKey(lines.get(index));
        if (matcher == null) {
            return false;
        }

        // 「値  # コメント」形式の行末コメントを保持する
        String trailingComment = "";
        String valuePart = matcher.group(3);
        int commentAt = valuePart.indexOf("  #");
        if (commentAt >= 0) {
            trailingComment = valuePart.substring(commentAt);
        }

        lines.set(index, matcher.group(1) + matcher.group(2) + ": " + rawValue + trailingComment);
        return true;
    }

    /**
     * キーを、説明コメントと配下ブロックごと削除します。
     * <p>
     * ファイル先頭のキーを削除すると、その直上にあるファイル全体の見出しコメントも
     * 一緒に消える点に注意してください。
     * </p>
     *
     * @param path ドット区切りのパス
     * @return 削除した場合は true
     */
    public boolean remove(String path) {
        int index = indexOf(path);
        if (index < 0) {
            return false;
        }
        int end = blockEndExclusive(index);
        int start = leadingCommentStart(index);
        lines.subList(start, end).clear();
        return true;
    }

    /**
     * キー名だけを変更します。配下のブロックとコメントはそのまま残ります。
     * <p>
     * 同じ親の下での改名に使います。別のセクションへ移す場合は
     * {@link #extractBlock(String)} で取り出し、{@link #remove(String)} してから
     * {@link #insertInto(String, List)} で入れ直してください。
     * </p>
     *
     * @param path ドット区切りのパス
     * @param newKey 新しいキー名（末尾セグメントのみ）
     * @return 改名した場合は true
     */
    public boolean rename(String path, String newKey) {
        int index = indexOf(path);
        if (index < 0) {
            return false;
        }
        Matcher matcher = matchKey(lines.get(index));
        if (matcher == null) {
            return false;
        }
        lines.set(index, matcher.group(1) + newKey + ":" + matcher.group(3));
        return true;
    }

    /**
     * セクションの末尾に行ブロックを挿入します。
     *
     * @param parentPath 挿入先セクションのパス。ルート直下なら空文字
     * @param block 挿入する行（字下げ済みであること）
     * @return 挿入した場合は true。挿入先セクションが見つからなければ false
     */
    public boolean insertInto(String parentPath, List<String> block) {
        if (block.isEmpty()) {
            return false;
        }

        if (parentPath.isEmpty()) {
            if (!lines.isEmpty() && !isBlank(lines.get(lines.size() - 1))) {
                lines.add("");
            }
            lines.addAll(block);
            return true;
        }

        int index = indexOf(parentPath);
        if (index < 0) {
            return false;
        }
        lines.addAll(blockEndExclusive(index), block);
        return true;
    }

    /**
     * ファイル先頭（見出しコメントの直後）にキーを挿入します。
     * config-version のように、ファイルの一番上に置きたいものに使います。
     *
     * @param block 挿入する行
     */
    public void insertAtTop(List<String> block) {
        int insertAt = 0;
        while (insertAt < lines.size() && isComment(lines.get(insertAt))) {
            insertAt++;
        }

        List<String> toInsert = new ArrayList<>(block);
        // 見出しコメントと詰まって読みにくくならないよう前後に空行を確保する
        if (insertAt > 0) {
            toInsert.add(0, "");
        }
        if (insertAt < lines.size() && !isBlank(lines.get(insertAt))) {
            toInsert.add("");
        }
        lines.addAll(insertAt, toInsert);
    }

    /**
     * 行ブロック全体の字下げを増減させます。
     * 別ファイルから取り出したブロックを、階層の異なる場所へ差し込むときに使います。
     *
     * @param block 対象の行
     * @param delta 増やす字下げ幅（負なら減らす）
     * @return 字下げを調整した新しい行のリスト
     */
    public static List<String> reindent(List<String> block, int delta) {
        if (delta == 0) {
            return new ArrayList<>(block);
        }

        List<String> result = new ArrayList<>(block.size());
        for (String line : block) {
            if (isBlank(line)) {
                result.add(line);
            } else if (delta > 0) {
                result.add(" ".repeat(delta) + line);
            } else {
                int removable = Math.min(-delta, indentOf(line));
                result.add(line.substring(removable));
            }
        }
        return result;
    }
}
