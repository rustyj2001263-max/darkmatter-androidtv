package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber

// Per-user display preferences written by the AI Smart Picks script
// (/mnt/user/appdata/ai-smart-picks/smart_picks.py); must match PREFS_ID / PREFS_CLIENT there
// and the same constants in jellyfin-web src/controllers/home.js
private const val AI_PICKS_PREFS_ID = "dmmAiSmartPicks"
private const val AI_PICKS_PREFS_CLIENT = "dmm"
private const val AI_PICKS_PREFS_KEY = "aiSmartPicks"
private const val UP_NEXT_MOVIES_PREFS_KEY = "upNextMovies"

// JellyBridge libraries holding titles that are not in the library yet
private const val DISCOVER_MOVIES_LIBRARY = "Discover Movies"
private const val DISCOVER_SHOWS_LIBRARY = "Discover Shows"
private val DISCOVER_LIBRARIES = setOf(DISCOVER_MOVIES_LIBRARY, DISCOVER_SHOWS_LIBRARY)
private const val PLACEHOLDER_PATH_MARKER = "/JellyBridge/"

// Anime has its own libraries; any library with "anime" in its name gets the anime rows instead
private fun BaseItemDto.isAnimeLibrary() = name?.contains("anime", ignoreCase = true) == true

private const val ITEM_LIMIT_DISCOVER = 24
private const val ITEM_LIMIT_RECENT = 24

/**
 * A row showing a fixed, already ordered list of items.
 */
class HomeFragmentStaticItemsRow(
	private val title: String,
	private val items: List<BaseItemDto>,
) : HomeFragmentRow {
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val rowAdapter = ItemRowAdapter(context, items, cardPresenter, rowsAdapter, true)
		val row = ListRow(HeaderItem(title), rowAdapter)
		rowAdapter.setRow(row)
		rowAdapter.Retrieve()
		rowsAdapter.add(row)
	}
}

/**
 * Builds the Dark Matter Media home rows in the same order as the web client:
 * AI Smart Picks, Continue Watching, Up Next Movies, Up Next TV Shows, Up Next Anime, Recent Movies,
 * Recent TV Shows, Recent Anime, Discover Movies and Discover TV Shows. Empty rows are left out.
 */
class DarkMatterHomeRows(
	private val context: Context,
	private val api: ApiClient,
	private val helper: HomeFragmentHelper,
) {
	suspend fun load(userViews: Collection<BaseItemDto>): List<HomeFragmentRow> {
		val prefs = loadCustomPrefs()
		val rows = mutableListOf<HomeFragmentRow>()

		loadPrefsItems(prefs[AI_PICKS_PREFS_KEY])?.let {
			rows.add(HomeFragmentStaticItemsRow(context.getString(R.string.lbl_ai_smart_picks), it))
		}
		rows.add(helper.loadResumeVideo())
		loadPrefsItems(prefs[UP_NEXT_MOVIES_PREFS_KEY])?.let {
			rows.add(HomeFragmentStaticItemsRow(context.getString(R.string.lbl_up_next_movies), it))
		}
		nextUpRow(userViews, anime = false, context.getString(R.string.lbl_up_next_tv_shows))?.let { rows.add(it) }
		nextUpRow(userViews, anime = true, context.getString(R.string.lbl_up_next_anime))?.let { rows.add(it) }

		loadRecentItems(userViews, setOf(CollectionType.MOVIES), anime = false)?.let {
			rows.add(HomeFragmentStaticItemsRow(context.getString(R.string.lbl_recent_movies), it))
		}
		loadRecentItems(userViews, setOf(CollectionType.TVSHOWS), anime = false)?.let {
			rows.add(HomeFragmentStaticItemsRow(context.getString(R.string.lbl_recent_tv_shows), it))
		}
		loadRecentItems(userViews, setOf(CollectionType.MOVIES, CollectionType.TVSHOWS), anime = true)?.let {
			rows.add(HomeFragmentStaticItemsRow(context.getString(R.string.lbl_recent_anime), it))
		}

		userViews.firstOrNull { it.name == DISCOVER_MOVIES_LIBRARY }?.let {
			rows.add(discoverRow(it, BaseItemKind.MOVIE, context.getString(R.string.lbl_discover_movies)))
		}
		userViews.firstOrNull { it.name == DISCOVER_SHOWS_LIBRARY }?.let {
			rows.add(discoverRow(it, BaseItemKind.SERIES, context.getString(R.string.lbl_discover_tv_shows)))
		}

		return rows
	}

	private suspend fun loadCustomPrefs(): Map<String, String?> = try {
		api.displayPreferencesApi.getDisplayPreferences(
			displayPreferencesId = AI_PICKS_PREFS_ID,
			client = AI_PICKS_PREFS_CLIENT,
		).content.customPrefs
	} catch (err: ApiClientException) {
		Timber.e(err, "Unable to load Dark Matter Media home preferences")
		emptyMap()
	}

	/**
	 * Loads the comma-separated item ids stored by the script, keeping their order and
	 * dropping anything watched since. Returns null when there is nothing to show.
	 */
	private suspend fun loadPrefsItems(value: String?): List<BaseItemDto>? {
		val ids = value.orEmpty().split(',').mapNotNull { it.trim().toUUIDOrNull() }
		if (ids.isEmpty()) return null

		val items = try {
			api.itemsApi.getItems(
				ids = ids,
				fields = ItemRepository.browseFields,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
			).content.items
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to load Dark Matter Media home row items")
			return null
		}

		val order = ids.withIndex().associate { (index, id) -> id to index }
		return items
			.filterNot { it.userData?.played == true }
			.sortedBy { order[it.id] ?: Int.MAX_VALUE }
			.takeIf { it.isNotEmpty() }
	}

	/**
	 * Next episodes for the anime or the other TV libraries. A single library keeps the stock row, which
	 * refreshes itself after playback; several libraries are merged into one row.
	 */
	private suspend fun nextUpRow(userViews: Collection<BaseItemDto>, anime: Boolean, title: String): HomeFragmentRow? {
		val libraries = userViews.filter {
			it.collectionType == CollectionType.TVSHOWS && it.isAnimeLibrary() == anime && it.name !in DISCOVER_LIBRARIES
		}

		return when (libraries.size) {
			0 -> null
			1 -> helper.loadNextUp(title, libraries.single().id)
			else -> libraries
				.flatMap { library ->
					try {
						api.tvShowsApi.getNextUp(
							parentId = library.id,
							limit = ITEM_LIMIT_RECENT,
							fields = ItemRepository.browseFields,
							imageTypeLimit = 1,
							enableResumable = false,
						).content.items
					} catch (err: ApiClientException) {
						Timber.e(err, "Unable to load next up for ${library.name}")
						emptyList()
					}
				}
				.take(ITEM_LIMIT_RECENT)
				.takeIf { it.isNotEmpty() }
				?.let { HomeFragmentStaticItemsRow(title, it) }
		}
	}

	/**
	 * Newly added items from every anime or non-anime library of the given types, newest first. Each
	 * library is queried on its own because the server caps a combined "latest" list and JellyBridge
	 * placeholders would crowd out real additions; new episodes are grouped into their series by the server.
	 */
	private suspend fun loadRecentItems(
		userViews: Collection<BaseItemDto>,
		collectionTypes: Set<CollectionType>,
		anime: Boolean,
	): List<BaseItemDto>? {
		val items = userViews
			.filter { it.collectionType in collectionTypes && it.isAnimeLibrary() == anime }
			.flatMap { library ->
				try {
					api.userLibraryApi.getLatestMedia(
						parentId = library.id,
						includeItemTypes = listOf(
							if (library.collectionType == CollectionType.MOVIES) BaseItemKind.MOVIE else BaseItemKind.EPISODE
						),
						fields = ItemRepository.browseFields + ItemFields.PATH,
						imageTypeLimit = 1,
						groupItems = true,
						limit = ITEM_LIMIT_RECENT,
					).content
				} catch (err: ApiClientException) {
					Timber.e(err, "Unable to load recent items for ${library.name}")
					emptyList()
				}
			}

		return items
			.filterNot { it.path?.contains(PLACEHOLDER_PATH_MARKER) == true }
			// Series report when their newest episode arrived; movies only have their own date
			.sortedByDescending { it.dateLastMediaAdded ?: it.dateCreated }
			.take(ITEM_LIMIT_RECENT)
			.takeIf { it.isNotEmpty() }
	}

	private fun discoverRow(library: BaseItemDto, type: BaseItemKind, title: String): HomeFragmentRow {
		val query = GetItemsRequest(
			parentId = library.id,
			recursive = true,
			includeItemTypes = setOf(type),
			sortBy = setOf(ItemSortBy.DATE_CREATED),
			sortOrder = setOf(SortOrder.DESCENDING),
			limit = ITEM_LIMIT_DISCOVER,
			fields = ItemRepository.browseFields,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(title, query, 0))
	}
}
