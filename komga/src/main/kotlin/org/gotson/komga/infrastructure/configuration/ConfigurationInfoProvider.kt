package org.gotson.komga.infrastructure.configuration

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize

@Component
class ConfigurationInfoProvider(
  private val komgaProperties: KomgaProperties,
) : InfoContributor {
  @Autowired
  private lateinit var env: Environment

  private val memoryLimit = 1_000_000

  private fun thumbnailsInfo(): HashMap<String, Any> =
    HashMap<String, Any>().also {
      it["mode"] = komgaProperties.thumbnailGeneration.saveMode.toString()
      val sizeLimit = env.getProperty("spring.servlet.multipart.max-file-size") ?: "1MB"
      val fileSizeLimit = if (sizeLimit == "-1") null else DataSize.parse(sizeLimit).toBytes()
      if (fileSizeLimit != null) {
        if (it["mode"].toString().contains("MEMORY")) {
          it["limit"] = if (fileSizeLimit > memoryLimit) memoryLimit else fileSizeLimit
        } else {
          it["limit"] = fileSizeLimit
        }
      } else {
        it["limit"] = -1
      }
    }

  override fun contribute(builder: Info.Builder?) {
    val wrapper = HashMap<String, Any>()
    // Get spring.
    wrapper["thumbnail"] = thumbnailsInfo()
    builder?.withDetails(wrapper)
  }
}
