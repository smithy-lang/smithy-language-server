package software.amazon.smithy.lsp.ext;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;

/**
 * 
 * { ... normal smithy-build.json stuff ... "extensions": { "repositories":
 * ["maven"] } }
 */

public final class SmithyBuildExtensions {
  private List<String> repositories;
  private List<String> artifacts;

  public SmithyBuildExtensions() {
    this.artifacts = new ArrayList<String>();
    this.repositories = new ArrayList<String>();
  }

  // this is wasteful (we could've maintained a recent copy)
  // but for the number of elements we have overhead is near-0
  public ImmutableList<String> getRepositories() {
    return ImmutableList.copyOf(repositories);
  }

  public ImmutableList<String> getArtifacts() {
    return ImmutableList.copyOf(artifacts);
  }

  public SmithyBuildExtensions addRepository(String repo) {
    if (!repositories.contains(repo)) {
      this.repositories.add(repo);
    }

    return this;
  }

  public SmithyBuildExtensions addArtifact(String artifact) {
    if (!artifacts.contains(artifact)) {
      this.artifacts.add(artifact);
    }

    return this;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof SmithyBuildExtensions) {
      SmithyBuildExtensions cast = (SmithyBuildExtensions) obj;
      if (this.getRepositories().equals(cast.getRepositories()))
        return true;
      else
        return false;
    } else {
      return false;
    }
  }

  public String dumpJson() {
    JsonObject root = new JsonObject();

    JsonWriter writer = new JsonWriter(new StringWriter());

    JsonArray repos = new JsonArray();
    JsonArray modules = new JsonArray();

    for (String repo : repositories) {
      repos.add(new JsonPrimitive(repo));
    }

    for (String artifact : artifacts) {
      modules.add(new JsonPrimitive(artifact));
    }

    JsonObject extensions = new JsonObject();
    extensions.add("repositories", repos);
    extensions.add("artifacts", modules);

    root.add("extensions", extensions);

    return root.toString();
  }

  private static SmithyBuildExtensions load(Reader json) throws ValidationException {
    JsonParser parser = new JsonParser();

    JsonObject obj = parser.parse(json).getAsJsonObject();

    SmithyBuildExtensions base = SmithyBuildExtensions.create();

    if (obj.has("extensions")) {
      JsonObject ext = obj.get("extensions").getAsJsonObject();

      if (ext.has("repositories")) {
        JsonArray repos = ext.get("repositories").getAsJsonArray();
        for (JsonElement repoJson : repos) {
          base.addRepository(repoJson.getAsString());
        }
      }

      if (ext.has("artifacts")) {
        JsonArray artifacts = ext.get("artifacts").getAsJsonArray();
        for (JsonElement artifactJson : artifacts) {
          base.addArtifact(artifactJson.getAsString());
        }
      }
    }

    return base;
  }

  public static SmithyBuildExtensions load(File file) throws FileNotFoundException, ValidationException {
    return load(new FileReader(file));
  }

  public static SmithyBuildExtensions load(String json) throws ValidationException {
    return load(new StringReader(json));
  }

  public static SmithyBuildExtensions create() {
    return new SmithyBuildExtensions();
  }

  @Override
  public String toString() {
    return "SmithyBuildExtensions(repositories=" + repositories.toString() + ", artifacts=" + artifacts.toString()
        + ")";
  }
}
