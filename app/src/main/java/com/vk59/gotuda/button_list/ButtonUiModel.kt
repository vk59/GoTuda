package com.vk59.gotuda.button_list

data class ButtonUiModel(
  val id: String,
  val title: String,
  val subtitle: String? = null,
  val onClick: () -> Unit = {}
)