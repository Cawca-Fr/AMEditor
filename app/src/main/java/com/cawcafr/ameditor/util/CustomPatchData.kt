package com.cawcafr.ameditor.util

import java.io.Serializable

data class CustomPatchData(
    val itemsToDelete: MutableSet<String> = mutableSetOf(), // Liste des android:name à supprimer
    val itemsToDisable: MutableSet<String> = mutableSetOf() // Liste des android:name à désactiver
) : Serializable