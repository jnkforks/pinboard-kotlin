package com.fibelatti.pinboard.features.posts.data

import androidx.annotation.VisibleForTesting
import com.fibelatti.core.extension.orZero
import com.fibelatti.core.functional.Result
import com.fibelatti.core.functional.getOrDefault
import com.fibelatti.core.functional.getOrNull
import com.fibelatti.core.functional.getOrThrow
import com.fibelatti.core.functional.map
import com.fibelatti.core.functional.mapCatching
import com.fibelatti.core.functional.onSuccess
import com.fibelatti.pinboard.core.AppConfig.API_BASE_URL_LENGTH
import com.fibelatti.pinboard.core.AppConfig.API_MAX_LENGTH
import com.fibelatti.pinboard.core.AppConfig.API_MAX_URI_LENGTH
import com.fibelatti.pinboard.core.AppConfig.API_PAGE_SIZE
import com.fibelatti.pinboard.core.AppConfig.PinboardApiLiterals
import com.fibelatti.pinboard.core.android.ConnectivityInfoProvider
import com.fibelatti.pinboard.core.di.IoScope
import com.fibelatti.pinboard.core.extension.containsHtmlChars
import com.fibelatti.pinboard.core.extension.replaceHtmlChars
import com.fibelatti.pinboard.core.functional.resultFrom
import com.fibelatti.pinboard.core.network.ApiException
import com.fibelatti.pinboard.core.network.ApiResultCodes
import com.fibelatti.pinboard.core.network.InvalidRequestException
import com.fibelatti.pinboard.core.network.RateLimitRunner
import com.fibelatti.pinboard.core.network.resultFromNetwork
import com.fibelatti.pinboard.core.util.DateFormatter
import com.fibelatti.pinboard.features.posts.data.model.PostDto
import com.fibelatti.pinboard.features.posts.data.model.PostDtoMapper
import com.fibelatti.pinboard.features.posts.data.model.SuggestedTagDtoMapper
import com.fibelatti.pinboard.features.posts.domain.PostsRepository
import com.fibelatti.pinboard.features.posts.domain.model.Post
import com.fibelatti.pinboard.features.posts.domain.model.PostListResult
import com.fibelatti.pinboard.features.posts.domain.model.SuggestedTags
import com.fibelatti.pinboard.features.tags.domain.model.Tag
import com.fibelatti.pinboard.features.user.domain.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

class PostsDataSource @Inject constructor(
    private val userRepository: UserRepository,
    private val postsApi: PostsApi,
    private val postsDao: PostsDao,
    private val postDtoMapper: PostDtoMapper,
    private val suggestedTagDtoMapper: SuggestedTagDtoMapper,
    private val dateFormatter: DateFormatter,
    private val connectivityInfoProvider: ConnectivityInfoProvider,
    private val rateLimitRunner: RateLimitRunner,
    @IoScope private val pagedRequestsScope: CoroutineScope
) : PostsRepository {

    companion object {
        private const val SERVER_DOWN_TIMEOUT = 10_000L
    }

    override suspend fun update(): Result<String> {
        return resultFromNetwork {
            withContext(Dispatchers.IO) {
                withTimeout(SERVER_DOWN_TIMEOUT) {
                    postsApi.update().updateTime
                }
            }
        }
    }

    override suspend fun add(
        url: String,
        title: String,
        description: String?,
        private: Boolean?,
        readLater: Boolean?,
        tags: List<Tag>?,
        replace: Boolean
    ): Result<Post> {

        val trimmedTitle = title.take(API_MAX_LENGTH)
        val privateLiteral = private?.let {
            if (private) PinboardApiLiterals.NO else PinboardApiLiterals.YES
        }
        val readLaterLiteral = readLater?.let {
            if (readLater) PinboardApiLiterals.YES else PinboardApiLiterals.NO
        }
        val trimmedTags = tags?.joinToString(
            PinboardApiLiterals.TAG_SEPARATOR_REQUEST,
            transform = Tag::name
        )?.take(API_MAX_LENGTH)
        val replaceLiteral = if (replace) PinboardApiLiterals.YES else PinboardApiLiterals.NO

        // The API abuses GET, this aims to avoid getting 414 errors
        val remainingLength = API_MAX_URI_LENGTH - API_BASE_URL_LENGTH - url.length -
            trimmedTitle.length - privateLiteral?.length.orZero() - readLaterLiteral?.length.orZero() -
            trimmedTags?.length.orZero() - replaceLiteral.length

        return resultFromNetwork {
            withContext(Dispatchers.IO) {
                val result = withTimeout(SERVER_DOWN_TIMEOUT) {
                    postsApi.add(
                        url = url,
                        title = trimmedTitle,
                        description = description?.take(remainingLength),
                        public = privateLiteral,
                        readLater = readLaterLiteral,
                        tags = trimmedTags,
                        replace = replaceLiteral
                    )
                }

                when (result.resultCode) {
                    ApiResultCodes.DONE.code -> {
                        update().getOrNull()?.let(userRepository::setLastUpdate)
                        postsApi.getPost(url).posts
                            .also(postsDao::savePosts)
                            .first().let(postDtoMapper::map)
                    }
                    ApiResultCodes.ITEM_ALREADY_EXISTS.code -> getPost(url).getOrThrow()
                    else -> throw ApiException()
                }
            }
        }
    }

    override suspend fun delete(url: String): Result<Unit> {
        return resultFromNetwork {
            val result = withContext(Dispatchers.IO) {
                postsApi.delete(url)
            }

            if (result.resultCode == ApiResultCodes.DONE.code) {
                withContext(Dispatchers.IO) {
                    postsDao.deletePost(url)
                }
            } else {
                throw ApiException()
            }
        }.onSuccess { update().getOrNull()?.let(userRepository::setLastUpdate) }
    }

    override suspend fun getAllPosts(
        newestFirst: Boolean,
        searchTerm: String,
        tags: List<Tag>?,
        untaggedOnly: Boolean,
        publicPostsOnly: Boolean,
        privatePostsOnly: Boolean,
        readLaterOnly: Boolean,
        countLimit: Int,
        pageLimit: Int,
        pageOffset: Int
    ): Flow<Result<PostListResult?>> {
        val isConnected = connectivityInfoProvider.isConnected()
        val localData: suspend (upToDate: Boolean) -> Result<PostListResult?> =
            { upToDate: Boolean ->
                getLocalData(
                    newestFirst,
                    searchTerm,
                    tags,
                    untaggedOnly,
                    publicPostsOnly,
                    privatePostsOnly,
                    readLaterOnly,
                    countLimit,
                    pageLimit,
                    pageOffset,
                    upToDate
                )
            }

        if (!isConnected) {
            return flowOf(localData(true))
        }

        val userLastUpdate = userRepository.getLastUpdate().takeIf(String::isNotBlank)
        val apiLastUpdate = update().getOrDefault(dateFormatter.nowAsTzFormat())

        if (userLastUpdate != null && userLastUpdate == apiLastUpdate) {
            return flowOf(localData(true))
        }

        return getAllFromApi(localData, apiLastUpdate)
    }

    @VisibleForTesting
    fun getAllFromApi(
        localData: suspend (upToDate: Boolean) -> Result<PostListResult?>,
        apiLastUpdate: String
    ): Flow<Result<PostListResult?>> {
        pagedRequestsScope.coroutineContext.cancelChildren()
        val apiData = suspend {
            resultFromNetwork {
                withContext(Dispatchers.IO) {
                    postsApi.getAllPosts(offset = 0, limit = API_PAGE_SIZE)
                }
            }.mapCatching { posts ->
                withContext(Dispatchers.IO) {
                    postsDao.deleteAllPosts()
                    savePosts(posts)
                }

                userRepository.setLastUpdate(apiLastUpdate)

                if (posts.size == API_PAGE_SIZE) {
                    getAdditionalPages()
                }
            }.map { localData(true) }
        }

        return flow {
            emit(localData(false))
            emit(apiData())
        }
    }

    @VisibleForTesting
    fun getAdditionalPages() {
        pagedRequestsScope.launch {
            try {
                var currentOffset = API_PAGE_SIZE

                while (currentOffset != 0) {
                    val additionalPosts = rateLimitRunner.run {
                        postsApi.getAllPosts(offset = currentOffset, limit = API_PAGE_SIZE)
                    }

                    savePosts(additionalPosts)

                    if (additionalPosts.size == API_PAGE_SIZE) {
                        currentOffset += additionalPosts.size
                    } else {
                        currentOffset = 0
                    }
                }
            } catch (ignored: Exception) {
                // If it fails it can be resumed later
            }
        }
    }

    @VisibleForTesting
    fun savePosts(posts: List<PostDto>) {
        val updatedPosts = posts.map { post ->
            if (post.tags.containsHtmlChars()) {
                post.copy(tags = post.tags.replaceHtmlChars())
            } else {
                post
            }
        }
        postsDao.savePosts(updatedPosts)
    }

    @VisibleForTesting
    suspend fun getLocalDataSize(
        searchTerm: String,
        tags: List<Tag>?,
        untaggedOnly: Boolean,
        publicPostsOnly: Boolean,
        privatePostsOnly: Boolean,
        readLaterOnly: Boolean,
        countLimit: Int
    ): Int = withContext(Dispatchers.IO) {
        postsDao.getPostCount(
            term = PostsDao.preFormatTerm(searchTerm),
            tag1 = tags.getAndFormat(0),
            tag2 = tags.getAndFormat(1),
            tag3 = tags.getAndFormat(2),
            untaggedOnly = untaggedOnly,
            publicPostsOnly = publicPostsOnly,
            privatePostsOnly = privatePostsOnly,
            readLaterOnly = readLaterOnly,
            limit = countLimit
        )
    }

    @VisibleForTesting
    suspend fun getLocalData(
        newestFirst: Boolean,
        searchTerm: String,
        tags: List<Tag>?,
        untaggedOnly: Boolean,
        publicPostsOnly: Boolean,
        privatePostsOnly: Boolean,
        readLaterOnly: Boolean,
        countLimit: Int,
        pageLimit: Int,
        pageOffset: Int,
        upToDate: Boolean
    ): Result<PostListResult?> {
        return resultFrom {
            val localDataSize = getLocalDataSize(
                searchTerm,
                tags,
                untaggedOnly,
                publicPostsOnly,
                privatePostsOnly,
                readLaterOnly,
                countLimit
            )

            if (localDataSize > 0) {
                val localData = withContext(Dispatchers.IO) {
                    postsDao.getAllPosts(
                        newestFirst = newestFirst,
                        term = PostsDao.preFormatTerm(searchTerm),
                        tag1 = tags.getAndFormat(0),
                        tag2 = tags.getAndFormat(1),
                        tag3 = tags.getAndFormat(2),
                        untaggedOnly = untaggedOnly,
                        publicPostsOnly = publicPostsOnly,
                        privatePostsOnly = privatePostsOnly,
                        readLaterOnly = readLaterOnly,
                        limit = pageLimit,
                        offset = pageOffset
                    )
                }.let(postDtoMapper::mapList)

                PostListResult(totalCount = localDataSize, posts = localData, upToDate = upToDate)
            } else {
                null
            }
        }
    }

    private fun List<Tag>?.getAndFormat(index: Int): String {
        return this?.getOrNull(index)?.name?.let(PostsDao.Companion::preFormatTag).orEmpty()
    }

    override suspend fun getPost(url: String): Result<Post> {
        return resultFromNetwork {
            withContext(Dispatchers.IO) {
                postsDao.getPost(url) ?: postsApi.getPost(url).posts.firstOrNull()
            }?.let(postDtoMapper::map) ?: throw InvalidRequestException()
        }
    }

    override suspend fun searchExistingPostTag(tag: String): Result<List<String>> {
        return resultFromNetwork {
            val concatenatedTags = withContext(Dispatchers.IO) {
                postsDao.searchExistingPostTag(PostsDao.preFormatTag(tag))
            }

            concatenatedTags.flatMap { it.replaceHtmlChars().split(" ") }
                .filter { it.startsWith(tag, ignoreCase = true) }
                .distinct()
                .sorted()
        }
    }

    override suspend fun getSuggestedTagsForUrl(url: String): Result<SuggestedTags> {
        return resultFromNetwork {
            withContext(Dispatchers.IO) {
                postsApi.getSuggestedTagsForUrl(url)
            }.let(suggestedTagDtoMapper::map)
        }
    }

    override suspend fun clearCache(): Result<Unit> {
        return resultFrom {
            withContext(Dispatchers.IO) {
                postsDao.deleteAllPosts()
            }
        }
    }
}
