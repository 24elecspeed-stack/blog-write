package com.keyword.research

// 좌표 하나를 변환한 결과 - "구 동" 단위(dong)와, 그 안의 "구 동 번지수" 단위(address)를 한 번의
// API 호출로 함께 담아온다. "위치로 사진 폴더 만들기"에서 동을 고른 다음, 그 안을 다시 번지수별
// 폴더로 나눌 때 추가 API 호출 없이 이미 가진 값을 그대로 쓰기 위함.
data class GeoAddress(val dong: String, val address: String)
