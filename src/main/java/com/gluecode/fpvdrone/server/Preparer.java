package com.gluecode.fpvdrone.server;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

@FunctionalInterface
public interface Preparer {
  void accept(PreparedStatement t) throws SQLException;

  default Preparer andThen(Preparer after) {
    Objects.requireNonNull(after);
    return (PreparedStatement t) -> { accept(t); after.accept(t); };
  }
}
