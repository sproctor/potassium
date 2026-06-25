package com.seanproctor.potassium

// We write explicitly about OptIn, because IDEA doesn't suggest it.
@RequiresOptIn(
    "This library is experimental and can be unstable. " +
        "Add @OptIn(com.seanproctor.potassium.ExperimentalPotassiumLibrary::class) annotation.",
)
annotation class ExperimentalPotassiumLibrary
