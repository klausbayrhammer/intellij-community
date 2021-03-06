package org.jetbrains.io.jsonRpc;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.io.JsonReaderEx;

import java.io.IOException;

public interface JsonServiceInvocator {
  void invoke(@NotNull String command, @NotNull Client client, @NotNull JsonReaderEx reader, @NotNull String message, int messageId) throws IOException;
}
