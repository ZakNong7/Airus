package com.zaknong.airus.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.zaknong.airus.R

sealed class Screens(
    @StringRes val titleId: Int,
    @DrawableRes val iconIdInactive: Int,
    @DrawableRes val iconIdActive: Int,
    val route: String,
) {
    object Home : Screens(
        titleId = R.string.home,
        iconIdInactive = R.drawable.ic_home,
        iconIdActive = R.drawable.ic_home,
        route = "home",
    )

    object Search : Screens(
        titleId = R.string.search,
        iconIdInactive = R.drawable.ic_search,
        iconIdActive = R.drawable.ic_search,
        route = "search",
    )

    object Library : Screens(
        titleId = R.string.library,
        iconIdInactive = R.drawable.ic_grid,
        iconIdActive = R.drawable.ic_grid,
        route = "library",
    )

    companion object {
        val MainScreens = listOf(Home, Search, Library)
    }
}
