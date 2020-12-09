package software.amazon.smithy.lsp;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class Utils {

  public static <U> CompletableFuture<U> completableFuture(U value) {
    Supplier<U> supplier = new Supplier<U>() {
      public U get() {
        return value;
      }
    };

    return CompletableFuture.supplyAsync(supplier);
  }
}
