package io.heckel.ntfy.emoji;

import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.List;

/**
 * This class represents an emoji.<br>
 * <br>
 * This object is immutable so it can be used safely in a multithreaded context.
 *
 * @author Vincent DURMONT [vdurmont@gmail.com]
 */
public class Emoji {
  private final List<String> aliases;
  private final String unicode;

  /**
   * Constructor for the Emoji.
   *
   * @param aliases             the aliases for this emoji
   * @param bytes               the bytes that represent the emoji
   */
  protected Emoji(
    List<String> aliases,
    byte... bytes
  ) {
    this.aliases = Collections.unmodifiableList(aliases);

    int count = 0;
    try {
      this.unicode = new String(bytes, "UTF-8");
      int stringLength = getUnicode().length();
      String[] pointCodes = new String[stringLength];
      String[] pointCodesHex = new String[stringLength];

      for (int offset = 0; offset < stringLength; ) {
        final int codePoint = getUnicode().codePointAt(offset);

        pointCodes[count] = String.format("&#%d;", codePoint);
        pointCodesHex[count++] = String.format("&#x%x;", codePoint);

        offset += Character.charCount(codePoint);
      }
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the aliases of the emoji
   *
   * @return the aliases (unmodifiable)
   */
  public List<String> getAliases() {
    return this.aliases;
  }

  /**
   * Returns the unicode representation of the emoji
   *
   * @return the unicode representation
   */
  public String getUnicode() {
    return this.unicode;
  }

  @Override
  public boolean equals(Object other) {
    return !(other == null || !(other instanceof Emoji)) &&
      ((Emoji) other).getUnicode().equals(getUnicode());
  }

  @Override
  public int hashCode() {
    return unicode.hashCode();
  }

  /**
   * Returns the String representation of the Emoji object.
   * @return the string representation
   */
  @Override
  public String toString() {
    return "Emoji{aliases=" + aliases + ", unicode='" + unicode + "'}";
  }
}
