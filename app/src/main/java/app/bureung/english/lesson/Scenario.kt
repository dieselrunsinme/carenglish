package app.bureung.english.lesson

/**
 * Where the family is going, as a kind of place, read from the navigation's destination name ("에버랜드" →
 * [Scenarios.OUTING]). There is no menu: when the destination tells nothing (no route, the daily kindergarten
 * nobody sets, Grandma's house found by the school next to it), the talk simply starts with "Where are you going
 * today?" and the child says it. Only the kind of place goes to the AI, never the destination's name.
 */
data class Scenario(
    val id: String,
    /** "할머니댁 가는 길", for the parent's screen */
    val placeKo: String,
    val emoji: String,
    /** what the AI is told about the trip */
    val situation: String,
    /** 부릉이 shows it knows where they are going ("I see you're going to Grandma's house!") */
    val seen: String,
    /** and asks about it */
    val question: String = "What are you going to do there?",
    /** words in a destination name that tell this kind of place */
    val keywords: List<String>,
) {
    fun opener(name: String, hour: Int): String = "${TalkRules.greeting(name, hour)} $seen $question"
}

object Scenarios {
    val OUTING = Scenario(
        id = "outing", placeKo = "놀러 가는 길", emoji = "🎡",
        situation = "The family is driving together to a fun place (an amusement park, a zoo, a park or a museum).",
        seen = "I see you're going somewhere fun!",
        keywords = listOf("놀이공원", "에버랜드", "롯데월드", "서울랜드", "랜드", "월드", "동물원", "대공원", "공원", "아쿠아리움", "수목원", "목장", "과학관", "박물관", "미술관", "체험"),
    )
    val TRIP = Scenario(
        id = "trip", placeKo = "여행 가는 길", emoji = "🧳",
        situation = "The family is going on a trip (to the airport, the beach or a campsite).",
        seen = "I see you're going on a trip!",
        keywords = listOf("공항", "호텔", "리조트", "해수욕장", "해변", "바다", "캠핑", "펜션", "휴게소", "터미널", "역"),
    )
    val GRANDMA = Scenario(
        id = "grandma", placeKo = "할머니댁 가는 길", emoji = "👵",
        situation = "The family is driving to visit Grandma and Grandpa.",
        seen = "I see you're going to Grandma's house!",
        keywords = listOf("할머니", "할아버지", "외가", "친가", "본가", "시댁", "처가"),
    )
    val MARKET = Scenario(
        id = "market", placeKo = "장 보러 가는 길", emoji = "🛒",
        situation = "The family is driving to the supermarket to buy food.",
        seen = "I see you're going to the supermarket!",
        keywords = listOf("마트", "코스트코", "트레이더스", "시장", "백화점"),
    )
    val FRIEND = Scenario(
        id = "friend", placeKo = "친구 만나러 가는 길", emoji = "🧒",
        situation = "The family is driving somewhere the child will meet friends and play.",
        seen = "I see you're going to play with friends!",
        keywords = listOf("놀이터", "키즈카페", "키즈 카페"),
    )
    val HOSPITAL = Scenario(
        id = "hospital", placeKo = "병원 가는 길", emoji = "🏥",
        situation = "The family is driving to see the doctor or the dentist. The child may feel a little sick or nervous: " +
            "that is expected here, so keep it light and reassuring, and do not ask about the illness in detail.",
        seen = "I see you're going to see the doctor.",
        question = "How are you feeling today?",
        keywords = listOf("병원", "의원", "소아과", "치과", "이비인후과"),
    )

    val all: List<Scenario> = listOf(OUTING, TRIP, GRANDMA, MARKET, FRIEND, HOSPITAL)

    fun byId(id: String?): Scenario? = all.firstOrNull { it.id == id }

    /** The kind of place a destination name tells ("우리 할머니댁" → grandma), or null when it tells nothing. */
    fun forDestination(name: String?): Scenario? {
        if (name.isNullOrBlank()) return null
        return all.firstOrNull { s -> s.keywords.any { name.contains(it, ignoreCase = true) } }
    }
}
