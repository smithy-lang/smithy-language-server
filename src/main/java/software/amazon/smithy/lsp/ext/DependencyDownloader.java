/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp.ext;

import coursierapi.Dependency;
import coursierapi.Fetch;
import coursierapi.MavenRepository;
import coursierapi.Repository;
import coursierapi.error.CoursierError;
import java.io.File;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public final class DependencyDownloader {
  private Fetch fetch;

  private DependencyDownloader(SmithyBuildExtensions extensions) throws ValidationException {
    fetch = Fetch.create();
    toRepositories(extensions.getMavenRepositories()).forEach(repo -> fetch.addRepositories(repo));
    toDependencies(extensions.getMavenDependencies()).forEach(dep -> fetch.addDependencies(dep));
  }

  private static List<Repository> toRepositories(List<String> repos) {
    // TODO: We assume maven repos by default.
    return repos.stream().map(MavenRepository::of).collect(Collectors.toList());
  }

  private static List<Dependency> toDependencies(List<String> deps) throws ValidationException {
    List<Dependency> lst = new LinkedList<Dependency>();

    for (String dep : deps) {
      // TODO: we assume Maven by default
      String[] parts = dep.split(":");
      if (parts.length != 3) {
        throw new ValidationException(
            "Dependencies in smithy-build.json must have the following format: org:module:version");
      } else {
        String org = parts[0];
        String module = parts[1];
        String version = parts[2];

        lst.add(Dependency.of(org, module, version));
      }
    }

    return Collections.unmodifiableList(lst);
  }

  public static DependencyDownloader create(SmithyBuildExtensions extensions) throws ValidationException {
    return new DependencyDownloader(extensions);
  }

  public List<File> download() throws CoursierError {
    return fetch.fetch();
  }
}
