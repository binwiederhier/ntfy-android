package io.heckel.ntfy.emoji;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the emojis from a JSON database.
 *
 * @author Vincent DURMONT [vdurmont@gmail.com]
 */
public class EmojiLoader {
  /**
   * No need for a constructor, all the methods are static.
   */
  private EmojiLoader() {}

  /**
   * Loads a JSONArray of emojis from an InputStream, parses it and returns the
   * associated list of {@link Emoji}s
   *
   * @param stream the stream of the JSONArray
   *
   * @return the list of {@link Emoji}s
   * @throws IOException if an error occurs while reading the stream or parsing
   * the JSONArray
   */
  public static List<Emoji> loadEmojis(InputStream stream) throws IOException, JSONException {
    JSONArray emojisJSON = new JSONArray(inputStreamToString(stream));
    List<Emoji> emojis = new ArrayList<Emoji>(emojisJSON.length());
    for (int i = 0; i < emojisJSON.length(); i++) {
      Emoji emoji = buildEmojiFromJSON(emojisJSON.getJSONObject(i));
      if (emoji != null) {
        emojis.add(emoji);
      }
    }
    return emojis;
  }

  private static String inputStreamToString(
    InputStream stream
  ) throws IOException {
    StringBuilder sb = new StringBuilder();
    InputStreamReader isr = new InputStreamReader(stream, "UTF-8");
    BufferedReader br = new BufferedReader(isr);
    String read;
    while((read = br.readLine()) != null) {
      sb.append(read);
    }
    br.close();
    return sb.toString();
  }

  protected static Emoji buildEmojiFromJSON(
    JSONObject json
  ) throws UnsupportedEncodingException, JSONException {
    if (!json.has("emoji")) {
      return null;
    }

    byte[] bytes = json.getString("emoji").getBytes("UTF-8");
    List<String> aliases = jsonArrayToStringList(json.getJSONArray("aliases"));
    return new Emoji(aliases, bytes);
  }

  private static List<String> jsonArrayToStringList(JSONArray array) throws JSONException {
    List<String> strings = new ArrayList<String>(array.length());
    for (int i = 0; i < array.length(); i++) {
      strings.add(array.getString(i));
    }
    return strings;
  }
}
