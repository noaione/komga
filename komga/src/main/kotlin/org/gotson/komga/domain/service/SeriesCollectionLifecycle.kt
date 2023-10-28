package org.gotson.komga.domain.service

import mu.KotlinLogging
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.DuplicateNameException
import org.gotson.komga.domain.model.SeriesCollection
import org.gotson.komga.domain.model.ThumbnailSeriesCollection
import org.gotson.komga.domain.persistence.SeriesCollectionRepository
import org.gotson.komga.domain.persistence.ThumbnailSeriesCollectionRepository
import org.gotson.komga.infrastructure.image.MosaicGenerator
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

@Service
class SeriesCollectionLifecycle(
  private val collectionRepository: SeriesCollectionRepository,
  private val thumbnailSeriesCollectionRepository: ThumbnailSeriesCollectionRepository,
  private val seriesLifecycle: SeriesLifecycle,
  private val mosaicGenerator: MosaicGenerator,
  private val eventPublisher: ApplicationEventPublisher,
  private val taskEmitter: TaskEmitter,
  private val transactionTemplate: TransactionTemplate,
) {

  @Throws(
    DuplicateNameException::class,
  )
  fun addCollection(collection: SeriesCollection): SeriesCollection {
    logger.info { "Adding new collection: $collection" }

    if (collectionRepository.existsByName(collection.name))
      throw DuplicateNameException("Collection name already exists")

    collectionRepository.insert(collection)

    eventPublisher.publishEvent(DomainEvent.CollectionAdded(collection))

    return collectionRepository.findByIdOrNull(collection.id)!!
  }

  fun updateCollection(toUpdate: SeriesCollection) {
    logger.info { "Update collection: $toUpdate" }

    val existing = collectionRepository.findByIdOrNull(toUpdate.id)
      ?: throw IllegalArgumentException("Cannot update collection that does not exist")

    if (!existing.name.equals(toUpdate.name, true) && collectionRepository.existsByName(toUpdate.name))
      throw DuplicateNameException("Collection name already exists")

    collectionRepository.update(toUpdate)

    eventPublisher.publishEvent(DomainEvent.CollectionUpdated(toUpdate))
  }

  fun deleteCollection(collection: SeriesCollection) {
    transactionTemplate.executeWithoutResult {
      thumbnailSeriesCollectionRepository.deleteByCollectionId(collection.id)
      collectionRepository.delete(collection.id)
    }
    eventPublisher.publishEvent(DomainEvent.CollectionDeleted(collection))
  }

  fun deleteEmptyCollections() {
    logger.info { "Deleting empty collections" }
    transactionTemplate.executeWithoutResult {
      val toDelete = collectionRepository.findAllEmpty()
      thumbnailSeriesCollectionRepository.deleteByCollectionIds(toDelete.map { it.id })
      collectionRepository.delete(toDelete.map { it.id })

      toDelete.forEach { eventPublisher.publishEvent(DomainEvent.CollectionDeleted(it)) }
    }
  }

  fun addThumbnail(thumbnail: ThumbnailSeriesCollection): ThumbnailSeriesCollection {
    when (thumbnail.type) {
      ThumbnailSeriesCollection.Type.USER_UPLOADED -> {
        thumbnailSeriesCollectionRepository.insert(thumbnail)
        if (thumbnail.selected) {
          thumbnailSeriesCollectionRepository.markSelected(thumbnail)
        }
      }
    }

    eventPublisher.publishEvent(DomainEvent.ThumbnailSeriesCollectionAdded(thumbnail))
    return thumbnail
  }

  fun markSelectedThumbnail(thumbnail: ThumbnailSeriesCollection) {
    thumbnailSeriesCollectionRepository.markSelected(thumbnail)
    eventPublisher.publishEvent(DomainEvent.ThumbnailSeriesCollectionAdded(thumbnail.copy(selected = true)))
  }

  fun deleteThumbnail(thumbnail: ThumbnailSeriesCollection) {
    thumbnailSeriesCollectionRepository.delete(thumbnail.id)
    thumbnailsHouseKeeping(thumbnail.collectionId)
    taskEmitter.deleteThumbnail(thumbnail.id, thumbnail.collectionId, ThumbnailLifecycle.Type.SERIES_COLLECTION)
    eventPublisher.publishEvent(DomainEvent.ThumbnailSeriesCollectionDeleted(thumbnail))
  }

  fun getThumbnailBytes(thumbnailId: String): ByteArray? =
    thumbnailSeriesCollectionRepository.findByIdOrNull(thumbnailId)?.thumbnail

  fun getThumbnailBytes(collection: SeriesCollection, userId: String): ByteArray {
    thumbnailSeriesCollectionRepository.findSelectedByCollectionIdOrNull(collection.id)?.let {
      if (it.thumbnail != null) return it.thumbnail
      if (it.url != null && it.exists()) {
        return Files.readAllBytes(Paths.get(it.url.toURI()))
      } else {
        logger.warn { "Selected thumbnail for series collection ${collection.name} is not available, falling back to mosaic" }
      }
    }

    val ids = with(mutableListOf<String>()) {
      while (size < 4) {
        this += collection.seriesIds.take(4)
      }
      this.take(4)
    }

    val images = ids.mapNotNull { seriesLifecycle.getThumbnailBytes(it, userId) }
    return mosaicGenerator.createMosaic(images)
  }

  private fun thumbnailsHouseKeeping(collectionId: String) {
    logger.info { "House keeping thumbnails for collection: $collectionId" }
    val all = thumbnailSeriesCollectionRepository.findAllByCollectionId(collectionId)

    val selected = all.filter { it.selected }
    when {
      selected.size > 1 -> {
        logger.info { "More than one thumbnail is selected, removing extra ones" }
        thumbnailSeriesCollectionRepository.markSelected(selected[0])
      }
      selected.isEmpty() && all.isNotEmpty() -> {
        logger.info { "Collection has no selected thumbnail, choosing one automatically" }
        thumbnailSeriesCollectionRepository.markSelected(all.first())
      }
    }
  }
}
