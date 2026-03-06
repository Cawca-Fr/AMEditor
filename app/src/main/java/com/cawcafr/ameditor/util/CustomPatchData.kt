package com.cawcafr.ameditor.util

import java.io.Serializable

data class CustomPatchData(
    val targets: List<PatchTarget> = emptyList()
) : Serializable

data class PatchTarget(
    val type: ActionType,
    val tagName: String,     // ex: "activity"
    val androidName: String?, // ex: "com.example.MainActivity" (pour double vérif)
    val parentName: String?,  // ex: "application"
    val occurrenceIndex: Int  // ex: C'est le 5ème élément de ce type rencontré dans le fichier
) : Serializable

enum class ActionType { DELETE, DISABLE }