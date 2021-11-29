package io.heckel.ntfy.util;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the loaded emojis and provides search functions.
 *
 * This class was originally written by Vincent DURMONT (vdurmont@gmail.com) as part of
 * https://github.com/vdurmont/emoji-java, but has since been heavily stripped and modified.
 */
public class EmojiManager {
  private static final String PATH = "/emoji.json"; // https://github.com/github/gemoji/blob/master/db/emoji.json
  private static final Map<String, Emoji> EMOJIS_BY_ALIAS = new HashMap<String, Emoji>();

  static {
    try {
      InputStream stream = EmojiLoader.class.getResourceAsStream(PATH);
      List<Emoji> emojis = EmojiLoader.loadEmojis(stream);
      for (Emoji emoji : emojis) {
        for (String alias : emoji.getAliases()) {
          EMOJIS_BY_ALIAS.put(alias, emoji);
        }
      }
      stream.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static Emoji getForAlias(String alias) {
    if (alias == null || alias.isEmpty()) {
      return null;
    }
    return EMOJIS_BY_ALIAS.get(trimAlias(alias));
  }

  private static String trimAlias(String alias) {
    int len = alias.length();
    return alias.substring(
            alias.charAt(0) == ':' ? 1 : 0,
            alias.charAt(len - 1) == ':' ? len - 1 : len);
  }
}
