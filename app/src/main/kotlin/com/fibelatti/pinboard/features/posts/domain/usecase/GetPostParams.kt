package com.fibelatti.pinboard.features.posts.domain.usecase

import com.fibelatti.pinboard.core.AppConfig.DEFAULT_PAGE_SIZE
import com.fibelatti.pinboard.features.appstate.NewestFirst
import com.fibelatti.pinboard.features.appstate.SortType
import com.fibelatti.pinboard.features.tags.domain.model.Tag

data class GetPostParams(
    val sorting: SortType = NewestFirst,
    val searchTerm: String = "",
    val tagParams: Tags = Tags.None,
    val visibilityParams: Visibility = Visibility.None,
    val readLater: Boolean = false,
    val limit: Int = DEFAULT_PAGE_SIZE,
    val offset: Int = 0
) {
    sealed class Tags {
        object Untagged : Tags()
        data class Tagged(val tags: List<Tag>?) : Tags()
        object None : Tags()
    }

    sealed class Visibility {
        object Public : Visibility()
        object Private : Visibility()
        object None : Visibility()
    }
}
