# Bas Play Extension Repair Plan

## Overview
- **Name:** Bas play
- **Base URL:** http://103.87.212.46
- **Type:** Video Portal (BDIX)
- **Status:** Initial implementation (v14.1)

## Architecture
- **Framework:** Kotlin-based Aniyomi extension.
- **Components:**
    - `getPopularAnime`: Scrapes hero slider (`vs-card`).
    - `getLatestUpdates`: Scrapes grid cards (`cp-card`) from homepage.
    - `getSearchAnime`: Uses `search.php?q=` for search and `tv.php?category=` or `category.php?category=` for filters.
    - `getEpisodeList`: 
        - Movies (`view.php?id=`): Single episode using `player.php` or `download.php` link.
        - TV Shows (`tview.php?series=`): Lists episodes from current season using `data-src` in `ep-item`.
    - `getVideoList`: Extracts direct video link from `player.php` or uses `data-src`.

## Known Limitations
- TV Series: Currently only lists episodes for the first/selected season. Mapping multiple seasons requires more complex pagination/fetching.
- Pagination: Site uses `cursor`-based pagination via `fetch_more.php`, not yet fully implemented in `getLatestUpdates`.

## Future Improvements
- Implement `cursor` pagination for infinite scroll.
- Add season selection support in `getEpisodeList`.
- Enhance search results by using TMDb for better metadata (optional).
