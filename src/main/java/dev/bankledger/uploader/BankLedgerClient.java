package dev.bankledger.uploader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Sends storage payloads to a Bank Ledger site. Uses RuneLite's shared OkHttp client and Gson. */
@Slf4j
class BankLedgerClient {
  static final Pattern PROFILE_ID = Pattern.compile("^[a-z0-9]{10}$");
  static final Pattern WRITE_KEY = Pattern.compile("^bl_[A-Za-z0-9]{20,100}$");
  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

  private final OkHttpClient http;
  private final Gson gson;

  BankLedgerClient(OkHttpClient http, Gson gson) {
    this.http = http;
    this.gson = gson;
  }

  /**
   * The storage upload URL for a profile, or null if the settings can't make a valid one. The site
   * must be https, except a local development server.
   */
  static HttpUrl uploadUrl(String siteUrl, String profileId) {
    return profileUrl(siteUrl, profileId, "storages");
  }

  /** Where Grand Exchange offer slots go; null on the same terms as {@link #uploadUrl}. */
  static HttpUrl geOffersUrl(String siteUrl, String profileId) {
    return profileUrl(siteUrl, profileId, "ge-offers");
  }

  /** The profile's dashboard page, for the sidebar's link; null on the same terms as {@link #uploadUrl}. */
  static HttpUrl dashboardUrl(String siteUrl, String profileId) {
    HttpUrl api = uploadUrl(siteUrl, profileId);
    return api == null
        ? null
        : api.newBuilder().encodedPath("/").addPathSegments("u/" + profileId.trim()).build();
  }

  private static HttpUrl profileUrl(String siteUrl, String profileId, String view) {
    if (profileId == null || !PROFILE_ID.matcher(profileId.trim()).matches()) {
      return null;
    }
    HttpUrl base = HttpUrl.parse(siteUrl == null ? "" : siteUrl.trim());
    if (base == null) {
      return null;
    }
    boolean local = base.host().equals("localhost") || base.host().equals("127.0.0.1");
    if (!base.isHttps() && !local) {
      return null;
    }
    return base.newBuilder()
        .encodedPath("/")
        .addPathSegments("api/u/" + profileId.trim() + "/" + view)
        .build();
  }

  void upload(HttpUrl url, String writeKey, Map<String, Object> body, Consumer<UploadResult> done) {
    Request request =
        new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + writeKey.trim())
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .build();

    http.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(@Nonnull Call call, @Nonnull IOException e) {
                log.debug("Bank Ledger upload failed", e);
                done.accept(new UploadResult(UploadResult.Outcome.FAILED, "Couldn't reach Bank Ledger", List.of(), 0));
              }

              @Override
              public void onResponse(@Nonnull Call call, @Nonnull Response response) {
                try (ResponseBody responseBody = response.body()) {
                  String text = responseBody == null ? "" : responseBody.string();
                  done.accept(interpret(response.code(), text));
                } catch (IOException e) {
                  done.accept(new UploadResult(UploadResult.Outcome.FAILED, "Couldn't read Bank Ledger's reply", List.of(), 0));
                }
              }
            });
  }

  /** Maps a status code and JSON body to an outcome. Package-private for tests. */
  UploadResult interpret(int status, String text) {
    JsonObject json;
    try {
      JsonElement parsed = gson.fromJson(text, JsonElement.class);
      json = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    } catch (RuntimeException e) {
      json = new JsonObject();
    }
    String error = json.has("error") ? json.get("error").getAsString() : null;
    List<String> stale = new ArrayList<>();
    if (json.has("staleStorages") && json.get("staleStorages").isJsonArray()) {
      JsonArray arr = json.getAsJsonArray("staleStorages");
      arr.forEach(e -> stale.add(e.getAsString()));
    }
    long worth = json.has("actualWorth") && !json.get("actualWorth").isJsonNull() ? json.get("actualWorth").getAsLong() : 0;

    if (status == 201) {
      return new UploadResult(UploadResult.Outcome.STORED, null, stale, worth);
    }
    if (status == 200 && json.has("unchanged")) {
      return new UploadResult(UploadResult.Outcome.UNCHANGED, null, stale, 0);
    }
    if (status == 401 || status == 404) {
      return new UploadResult(UploadResult.Outcome.REJECTED,
          status == 404 ? "Bank Ledger has no profile with that id" : "Bank Ledger rejected the write key", stale, 0);
    }
    if (status == 429 || status == 503) {
      return new UploadResult(UploadResult.Outcome.DEFERRED, error, stale, 0);
    }
    return new UploadResult(UploadResult.Outcome.FAILED, error != null ? error : "Bank Ledger returned " + status, stale, 0);
  }
}
