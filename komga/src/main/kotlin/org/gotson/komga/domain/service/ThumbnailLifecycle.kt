package org.gotson.komga.domain.service

import com.github.f4b6a3.tsid.TsidCreator
import mu.KotlinLogging
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.MediaNotReadyException
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.ThumbnailReadList
import org.gotson.komga.domain.model.ThumbnailSeries
import org.gotson.komga.domain.model.ThumbnailSeriesCollection
import org.gotson.komga.domain.persistence.ThumbnailBookRepository
import org.gotson.komga.domain.persistence.ThumbnailReadListRepository
import org.gotson.komga.domain.persistence.ThumbnailSeriesCollectionRepository
import org.gotson.komga.domain.persistence.ThumbnailSeriesRepository
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.gotson.komga.infrastructure.configuration.KomgaSettingsProvider
import org.gotson.komga.infrastructure.configuration.ThumbnailSaveMode
import org.gotson.komga.infrastructure.image.ImageAnalyzer
import org.gotson.komga.infrastructure.image.ImageConverter
import org.gotson.komga.infrastructure.image.ImageType
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.gotson.komga.infrastructure.mediacontainer.CoverExtractor
import org.gotson.komga.infrastructure.mediacontainer.MediaContainerExtractor
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.toPath
import kotlin.reflect.KClass
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

private val logger = KotlinLogging.logger {}

@Service
class ThumbnailLifecycle(
  extractors: List<MediaContainerExtractor>,
  private val contentDetector: ContentDetector,
  private val imageAnalyzer: ImageAnalyzer,
  private val imageConverter: ImageConverter,
  private val komgaProperties: KomgaProperties,
  private val komgaSettingsProvider: KomgaSettingsProvider,
  private val thumbnailBookRepository: ThumbnailBookRepository,
  private val thumbnailReadListRepository: ThumbnailReadListRepository,
  private val thumbnailSeriesCollectionRepository: ThumbnailSeriesCollectionRepository,
  private val thumbnailSeriesRepository: ThumbnailSeriesRepository,
) {
  private data class Result(val processed: Int, val hasMore: Boolean)
  private data class ThumbnailMetadata(val mediaType: String, val fileSize: Long, val dimension: Dimension)

  enum class Type {
    BOOK, SERIES, SERIES_COLLECTION, READ_LIST
  }

  data class Thumbnail(val id: String, val itemId: String, val type: Type, val url: URI)

  val supportedMediaTypes = extractors
    .flatMap { e -> e.mediaTypes().map { it to e } }
    .toMap()

  val thumbnailType = ImageType.JPEG

  /**
   * Find thumbnails without metadata (file size, dimensions, media type),
   * and attempt to fix it.
   *
   * @return true if more thumbnails need fixing
   */
  fun fixThumbnailsMetadata(): Boolean =
    fixThumbnailMetadataBook() || fixThumbnailMetadataSeries() || fixThumbnailMetadataCollection() || fixThumbnailMetadataReadList()

  fun fixThumbnailMetadataBook(): Boolean =
    fixThumbnailMetadata(
      ThumbnailBook::class,
      thumbnailBookRepository::findAllWithoutMetadata,
      { t ->
        when {
          t.thumbnail != null -> getMetadata(t.thumbnail)
          t.url != null -> getMetadata(t.url)
          else -> null
        }
      },
      { t, meta ->
        t.copy(
          mediaType = meta.mediaType,
          fileSize = meta.fileSize,
          dimension = meta.dimension,
        )
      },
      thumbnailBookRepository::updateMetadata,
    )

  fun fixThumbnailMetadataSeries(): Boolean =
    fixThumbnailMetadata(
      ThumbnailSeries::class,
      thumbnailSeriesRepository::findAllWithoutMetadata,
      { t ->
        when {
          t.thumbnail != null -> getMetadata(t.thumbnail)
          t.url != null -> getMetadata(t.url)
          else -> null
        }
      },
      { t, meta ->
        t.copy(
          mediaType = meta.mediaType,
          fileSize = meta.fileSize,
          dimension = meta.dimension,
        )
      },
      thumbnailSeriesRepository::updateMetadata,
    )

  fun fixThumbnailMetadataCollection(): Boolean =
    fixThumbnailMetadata(
      ThumbnailSeriesCollection::class,
      thumbnailSeriesCollectionRepository::findAllWithoutMetadata,
      { t ->
        when {
          t.thumbnail != null -> getMetadata(t.thumbnail)
          t.url != null -> getMetadata(t.url)
          else -> null
        }
      },
      { t, meta ->
        t.copy(
          mediaType = meta.mediaType,
          fileSize = meta.fileSize,
          dimension = meta.dimension,
        )
      },
      thumbnailSeriesCollectionRepository::updateMetadata,
    )

  fun fixThumbnailMetadataReadList(): Boolean =
    fixThumbnailMetadata(
      ThumbnailReadList::class,
      thumbnailReadListRepository::findAllWithoutMetadata,
      { t ->
        when {
          t.thumbnail != null -> getMetadata(t.thumbnail)
          t.url != null -> getMetadata(t.url)
          else -> null
        }
      },
      { t, meta ->
        t.copy(
          mediaType = meta.mediaType,
          fileSize = meta.fileSize,
          dimension = meta.dimension,
        )
      },
      thumbnailReadListRepository::updateMetadata,
    )

  private fun <T : Any> fixThumbnailMetadata(
    clazz: KClass<T>,
    fetcher: (Pageable) -> Page<T>,
    supplier: (T) -> ThumbnailMetadata?,
    copier: (T, ThumbnailMetadata) -> T,
    updater: (Collection<T>) -> Unit,
  ): Boolean {
    val (result, duration) = measureTimedValue {
      val thumbs = fetcher(Pageable.ofSize(1000))
      logger.info { "Fetched ${thumbs.numberOfElements} ${clazz.simpleName} to fix, total: ${thumbs.totalElements}" }

      val fixedThumbs = thumbs.mapNotNull {
        try {
          val meta = supplier(it)
          if (meta == null) null
          else copier(it, meta)
        } catch (e: Exception) {
          logger.error(e) { "Could not fix thumbnail: $it" }
          null
        }
      }

      updater(fixedThumbs)
      Result(fixedThumbs.size, (thumbs.numberOfElements < thumbs.totalElements))
    }
    logger.info { "Fixed ${result.processed} ${clazz.simpleName} in $duration" }
    return result.hasMore
  }

  private fun getMetadata(byteArray: ByteArray): ThumbnailMetadata =
    ThumbnailMetadata(
      mediaType = contentDetector.detectMediaType(byteArray.inputStream()),
      fileSize = byteArray.size.toLong(),
      dimension = imageAnalyzer.getDimension(byteArray.inputStream()) ?: Dimension(0, 0),
    )

  private fun getMetadata(url: URL): ThumbnailMetadata =
    ThumbnailMetadata(
      mediaType = contentDetector.detectMediaType(url.toURI().toPath()),
      fileSize = Path.of(url.toURI()).fileSize(),
      dimension = imageAnalyzer.getDimension(url.toURI().toPath().inputStream()) ?: Dimension(0, 0),
    )

  @Throws(MediaNotReadyException::class)
  fun generateThumbnail(book: BookWithMedia): ThumbnailBook {
    logger.info { "Generate thumbnail for book: $book" }

    val thumbnail = try {
      val extractor = supportedMediaTypes.getValue(book.media.mediaType!!)
      // try to get the cover from a CoverExtractor first
      var coverBytes: ByteArray? = if (extractor is CoverExtractor) {
        try {
          extractor.getCoverStream(book.book.path)
        } catch (e: Exception) {
          logger.warn(e) { "Error while extracting cover. Falling back to first page. Book: $book" }
          null
        }
      } else null
      // if no cover could be found, get the first page
      if (coverBytes == null) coverBytes = extractor.getEntryStream(book.book.path, book.media.pages.first().fileName)

      coverBytes.let { cover ->
        imageConverter.resizeImageToByteArray(cover, thumbnailType, komgaSettingsProvider.thumbnailSize.maxEdge)
      }
    } catch (ex: Exception) {
      logger.warn(ex) { "Could not generate thumbnail for book: $book" }
      null
    }

    val fileId = TsidCreator.getTsid256().toString()
    val imgDimension = thumbnail?.let { imageAnalyzer.getDimension(it.inputStream()) } ?: Dimension(0, 0)
    val imgFileSize = thumbnail?.size?.toLong() ?: 0

    val memoryThumb = ThumbnailBook(
      id = fileId,
      thumbnail = thumbnail,
      type = ThumbnailBook.Type.GENERATED,
      bookId = book.book.id,
      mediaType = thumbnailType.mediaType,
      dimension = imgDimension,
      fileSize = imgFileSize,
    )

    if (komgaProperties.thumbnailGeneration.saveMode == ThumbnailSaveMode.MEMORY) {
      return memoryThumb
    }

    if (thumbnail == null) {
      return memoryThumb
    }

    val thumbFileName = "${Type.BOOK}_${book.book.id}_$fileId.komgathumb"

    return saveThumbnailToDisk(thumbnail, thumbFileName)?.let {
      ThumbnailBook(
        id = fileId,
        url = it.toURL(),
        type = ThumbnailBook.Type.GENERATED,
        bookId = book.book.id,
        mediaType = thumbnailType.mediaType,
        dimension = imgDimension,
        fileSize = imgFileSize,
      )
    } ?: memoryThumb
  }

  fun saveThumbnailToDiskIfDiskMode(thumbnail: ByteArray, itemId: String, type: Type): Thumbnail? {
    val saveMode = komgaProperties.thumbnailGeneration.saveMode
    if (saveMode == ThumbnailSaveMode.ALWAYS_DISK || saveMode == ThumbnailSaveMode.DISK) {
      val thumbId = TsidCreator.getTsid256().toString()
      val itemUrl = saveThumbnailToDisk(thumbnail, "${type}_${itemId}_$thumbId.komgathumb")
      if (itemUrl != null) {
        return Thumbnail(thumbId, itemId, type, itemUrl)
      }
    }
    return null
  }

  fun moveGeneratedThumbnails() {
    val isInMemory = komgaProperties.thumbnailGeneration.saveMode == ThumbnailSaveMode.ALWAYS_MEMORY
    val isInDisk = komgaProperties.thumbnailGeneration.saveMode == ThumbnailSaveMode.ALWAYS_DISK
    thumbnailBookRepository.findAllByType(ThumbnailBook.Type.GENERATED).forEach {
      if (isInDisk && it.thumbnail != null) {
        val thumbUrl = saveThumbnailToDisk(it.thumbnail, "${Type.BOOK}_${it.bookId}.komgathumb")
        if (thumbUrl != null) {
          thumbnailBookRepository.update(it.copy(thumbnail = null, url = thumbUrl.toURL()))
        }
      } else if (isInMemory) {
        val thumbBytes = moveFromDiskToMemory(it.url)
        if (thumbBytes != null) {
          thumbnailBookRepository.update(it.copy(thumbnail = thumbBytes, url = null))
        }
      }
    }
    thumbnailBookRepository.findAllByType(ThumbnailBook.Type.USER_UPLOADED).forEach {
      if (isInDisk && it.thumbnail != null) {
        val thumbUrl = saveThumbnailToDisk(it.thumbnail, "${Type.BOOK}_${it.bookId}.komgathumb")
        if (thumbUrl != null) {
          thumbnailBookRepository.update(it.copy(thumbnail = null, url = thumbUrl.toURL()))
        }
      } else if (isInMemory) {
        val thumbBytes = moveFromDiskToMemory(it.url)
        if (thumbBytes != null) {
          thumbnailBookRepository.update(it.copy(thumbnail = thumbBytes, url = null))
        }
      }
    }
    thumbnailSeriesRepository.findAllByType(ThumbnailSeries.Type.USER_UPLOADED).forEach {
      if (isInDisk && it.thumbnail != null) {
        val thumbUrl = saveThumbnailToDisk(it.thumbnail, "${Type.SERIES}_${it.seriesId}.komgathumb")
        if (thumbUrl != null) {
          thumbnailSeriesRepository.update(it.copy(thumbnail = null, url = thumbUrl.toURL()))
        }
      } else if (isInMemory) {
        val thumbBytes = moveFromDiskToMemory(it.url)
        if (thumbBytes != null) {
          thumbnailSeriesRepository.update(it.copy(thumbnail = thumbBytes, url = null))
        }
      }
    }
    thumbnailReadListRepository.findAllByType(ThumbnailReadList.Type.USER_UPLOADED).forEach {
      if (isInDisk && it.thumbnail != null) {
        val thumbUrl = saveThumbnailToDisk(it.thumbnail, "${Type.READ_LIST}_${it.readListId}.komgathumb")
        if (thumbUrl != null) {
          thumbnailReadListRepository.update(it.copy(thumbnail = null, url = thumbUrl.toURL()))
        }
      } else if (isInMemory) {
        val thumbBytes = moveFromDiskToMemory(it.url)
        if (thumbBytes != null) {
          thumbnailReadListRepository.update(it.copy(thumbnail = thumbBytes, url = null))
        }
      }
    }
    thumbnailSeriesCollectionRepository.findAllByType(ThumbnailSeriesCollection.Type.USER_UPLOADED).forEach {
      if (isInDisk && it.thumbnail != null) {
        val thumbUrl = saveThumbnailToDisk(it.thumbnail, "${Type.SERIES_COLLECTION}_${it.collectionId}.komgathumb")
        if (thumbUrl != null) {
          thumbnailSeriesCollectionRepository.update(it.copy(thumbnail = null, url = thumbUrl.toURL()))
        }
      } else if (isInMemory) {
        val thumbBytes = moveFromDiskToMemory(it.url)
        if (thumbBytes != null) {
          thumbnailSeriesCollectionRepository.update(it.copy(thumbnail = thumbBytes, url = null))
        }
      }
    }
  }

  fun renameDiskThumbnailsFormatting() {
    // Check if file name is formatted correctly

    // Old format: TYPE_PARENTID.komgathumb
    // New format: TYPE_PARENTID_THUMBID.komgathumb
    // Reformat if needed

    val diskDirectory = komgaProperties.thumbnailGeneration.diskDirectory
    if (diskDirectory == null) {
      logger.warn { "Thumbnail generation is set to disk but no directory is configured!" }
      return
    }
    val thumbDisk = Paths.get(diskDirectory)
    if (thumbDisk.isDirectory() && thumbDisk.exists()) {
      logger.info { "Trying to update filename for disk thumbnails" }
      measureTime {
        thumbDisk.listDirectoryEntries("*.komgathumb").forEach { original ->
          // check if filename is formatted correctly
          val filename = original.fileName.toString()
          val stripFilename = filename
            .replace(".komgathumb", "")
            .replace("${Type.BOOK}_", "")
            .replace("${Type.SERIES_COLLECTION}_", "")
            .replace("${Type.SERIES}_", "")
            .replace("${Type.READ_LIST}_", "")
          val splitStripFilename = stripFilename.split("_")
          if (splitStripFilename.size == 1) {
            // Old format, reformat
            val itemId = splitStripFilename[0]
            if (filename.startsWith("${Type.BOOK}_")) {
              thumbnailBookRepository.findAllByBookId(itemId).forEach {
                if (it.url != null) {
                  val newFilename = "${Type.BOOK}_${it.bookId}_${it.id}.komgathumb"
                  val newPath = original.resolveSibling(newFilename)
                  try {
                    Files.move(original, newPath, StandardCopyOption.REPLACE_EXISTING)
                  } catch (ex: IOException) {
                    logger.error(ex) {
                      "Failed to move book thumbnail files from ${original.fileName} to $newFilename"
                    }
                  }
                  thumbnailBookRepository.update(it.copy(url = newPath.toUri().toURL()))
                }
              }
            } else if (filename.startsWith("${Type.SERIES_COLLECTION}_")) {
              thumbnailSeriesCollectionRepository.findAllByCollectionId(itemId).forEach {
                if (it.url != null) {
                  val newFilename = "${Type.BOOK}_${it.collectionId}_${it.id}.komgathumb"
                  val newPath = original.resolveSibling(newFilename)
                  try {
                    Files.move(original, newPath, StandardCopyOption.REPLACE_EXISTING)
                  } catch (ex: IOException) {
                    logger.error(ex) {
                      "Failed to move series collection thumbnail files from ${original.fileName} to $newFilename"
                    }
                  }
                  thumbnailSeriesCollectionRepository.update(it.copy(url = newPath.toUri().toURL()))
                }
              }
            } else if (filename.startsWith("${Type.SERIES}_")) {
              thumbnailSeriesRepository.findAllBySeriesId(itemId).forEach {
                if (it.url != null) {
                  val newFilename = "${Type.BOOK}_${it.seriesId}_${it.id}.komgathumb"
                  val newPath = original.resolveSibling(newFilename)
                  try {
                    Files.move(original, newPath, StandardCopyOption.REPLACE_EXISTING)
                  } catch (ex: IOException) {
                    logger.error(ex) {
                      "Failed to move series thumbnail files from ${original.fileName} to $newFilename"
                    }
                  }
                  thumbnailSeriesRepository.update(it.copy(url = newPath.toUri().toURL()))
                }
              }
            } else if (filename.startsWith("${Type.READ_LIST}_")) {
              thumbnailReadListRepository.findAllByReadListId(itemId).forEach {
                if (it.url != null) {
                  val newFilename = "${Type.BOOK}_${it.readListId}_${it.id}.komgathumb"
                  val newPath = original.resolveSibling(newFilename)
                  try {
                    Files.move(original, newPath, StandardCopyOption.REPLACE_EXISTING)
                  } catch (ex: IOException) {
                    logger.error(ex) {
                      "Failed to move series thumbnail files from ${original.fileName} to $newFilename"
                    }
                  }
                  thumbnailReadListRepository.update(it.copy(url = newPath.toUri().toURL()))
                }
              }
            }
          }
        }
      }.also {
        logger.info { "Finished updating filename for disk thumbnails in ${it.inWholeSeconds}s" }
      }
    }
  }

  private fun moveFromDiskToMemory(thumbUrl: URL?): ByteArray? {
    if (thumbUrl != null) {
      val thumbSize = Files.size(Paths.get(thumbUrl.toURI()))
      // check if file size is more than 1_000_000
      if (thumbSize > 1_000_000) {
        logger.warn { "Thumbnail size is more than 1MiB, cannot save in database, skipping!" }
        return null
      }
      val thumbnail = Files.readAllBytes(Paths.get(thumbUrl.toURI()))
      Files.deleteIfExists(Paths.get(thumbUrl.toURI()))
      return thumbnail
    }
    return null
  }

  private fun saveThumbnailToDisk(thumbnail: ByteArray, filename: String): URI? {
    if (!komgaProperties.thumbnailGeneration.saveMode.toString().contains("DISK")) {
      logger.warn { "Thumbnail generation is not set to disk, cannot save to disk!" }
      return null
    }

    val diskDirectory = komgaProperties.thumbnailGeneration.diskDirectory
    if (diskDirectory == null) {
      logger.warn { "Thumbnail generation is set to disk but no directory is configured!" }
      return null
    }
    val thumbDisk = Paths.get(diskDirectory)

    try {
      Files.createDirectories(thumbDisk)
    } catch (ex: FileAlreadyExistsException) {
      logger.warn { "Thumbnail generation is set to disk but the directory is a file!" }
      return null
    }

    val thumbDiskPath = thumbDisk.resolve(filename)
    return try {
      Files.write(thumbDiskPath, thumbnail, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      thumbDiskPath.toUri()
    } catch (ex: IOException) {
      logger.warn(ex) { "Could not save thumbnail to disk: $thumbDiskPath" }
      null
    }
  }

  fun deleteThumbnailFromDisk(thumbnail: Thumbnail) {
    val diskDirectory = komgaProperties.thumbnailGeneration.diskDirectory ?: return
    val thumbDisk = Paths.get(diskDirectory)

    val filename = "${thumbnail.type}_${thumbnail.itemId}_${thumbnail.id}.komgathumb"
    val thumbDiskPath = thumbDisk.resolve(filename)
    try {
      thumbDiskPath.deleteIfExists()
    } catch (ex: IOException) {
      logger.warn(ex) { "Could not delete thumbnail from disk: $thumbDiskPath" }
    }
  }
}
