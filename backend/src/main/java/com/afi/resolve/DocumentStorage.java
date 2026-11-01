package com.afi.resolve;

/** Private bytes only. Case authorisation stays in DocumentService for either storage backend. */
public interface DocumentStorage {
  void put(String key, byte[] bytes) throws Exception;

  byte[] read(String key) throws Exception;

  void delete(String key) throws Exception;

  static void validateKey(String key) {
    if (key == null || !key.matches("[0-9a-f-]{36}\\.pdf"))
      throw new ApiException(400, "INVALID_STORAGE_KEY", "Invalid private document reference");
  }
}
