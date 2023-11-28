package org.gotson.komga.interfaces.api.rest.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.gotson.komga.domain.model.AgeRestriction
import org.gotson.komga.domain.model.AllowExclude
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.UserRoles
import org.gotson.komga.infrastructure.configuration.ThumbnailSaveMode
import org.gotson.komga.infrastructure.security.KomgaPrincipal

data class UserDto(
  val id: String,
  val email: String,
  val roles: Set<String>,
  val sharedAllLibraries: Boolean,
  val sharedLibrariesIds: Set<String>,
  val labelsAllow: Set<String>,
  val labelsExclude: Set<String>,
  val ageRestriction: AgeRestrictionDto?,
  val thumbnailSaveMode: ThumbnailSaveMode,
)

data class AgeRestrictionDto(
  val age: Int,
  val restriction: AllowExclude,
)

fun AgeRestriction.toDto() = AgeRestrictionDto(age, restriction)

fun KomgaUser.toDto(thumbnailMode: ThumbnailSaveMode) =
  UserDto(
    id = id,
    email = email,
    roles = roles.map { it.name }.toSet() + "USER",
    sharedAllLibraries = sharedAllLibraries,
    sharedLibrariesIds = sharedLibrariesIds,
    labelsAllow = restrictions.labelsAllow,
    labelsExclude = restrictions.labelsExclude,
    ageRestriction = restrictions.ageRestriction?.toDto(),
    thumbnailSaveMode = thumbnailMode,
  )

fun KomgaPrincipal.toDto(thumbnailMode: ThumbnailSaveMode) =
  user.toDto(thumbnailMode = thumbnailMode)

data class UserCreationDto(
  @get:Email(regexp = ".+@.+\\..+") val email: String,
  @get:NotBlank val password: String,
  val roles: List<String> = emptyList(),
) {
  fun toDomain(): KomgaUser =
    KomgaUser(
      email,
      password,
      roles = UserRoles.valuesOf(roles),
    )
}

data class PasswordUpdateDto(
  @get:NotBlank val password: String,
)
