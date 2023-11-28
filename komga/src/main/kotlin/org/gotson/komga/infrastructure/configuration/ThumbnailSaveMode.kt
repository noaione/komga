package org.gotson.komga.infrastructure.configuration

enum class ThumbnailSaveMode {
  // Save in database, limited to 1MiB
  MEMORY,

  // Force save in database, move every thumbnail to database
  ALWAYS_MEMORY,

  // Save in disk, limited to disk size
  DISK,

  // Force save in disk, move every thumbnail to disk
  ALWAYS_DISK,
}
