package org.gotson.komga.interfaces.scheduler

import org.gotson.komga.application.tasks.HIGHEST_PRIORITY
import org.gotson.komga.application.tasks.HIGH_PRIORITY
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Profile("!test")
@Component
class ThumbnailGenerationController(
  private val komgaProperties: KomgaProperties,
  private val taskEmitter: TaskEmitter,
) {
  @EventListener(ApplicationReadyEvent::class)
  fun moveThumbnailsIfGenerationModeIsAlways() {
    if (komgaProperties.thumbnailGeneration.saveMode.toString().startsWith("ALWAYS")) {
      taskEmitter.moveGeneratedThumbnails(HIGH_PRIORITY)
    }
  }

  @EventListener(ApplicationReadyEvent::class)
  fun renameDiskThumbnailsFormatting() {
    if (komgaProperties.thumbnailGeneration.saveMode.toString().contains("DISK") && !komgaProperties.thumbnailGeneration.skipRenaming) {
      taskEmitter.renameDiskThumbnails(HIGHEST_PRIORITY)
    }
  }
}
