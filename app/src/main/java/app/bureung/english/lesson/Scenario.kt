package app.bureung.english.lesson

/**
 * A situation to talk about on the way somewhere (상황별 학습). 부릉이 opens with it, keeps the talk there, and invites
 * the child to say a few useful sentences, one at a time, as the talk allows. It is a separate mode the parent picks;
 * the destination in the navigation only marks one as suggested on the screen ("○○ 할머니댁" → [Scenarios.GRANDMA]).
 */
data class Scenario(
    val id: String,
    val titleKo: String,
    val emoji: String,
    /** one line for the parent's card */
    val descKo: String,
    /** what the AI is told about the trip */
    val situation: String,
    /** 부릉이's first question, after the greeting */
    val question: String,
    /** sentences the child is invited to say */
    val targets: List<String>,
    /** words in a destination name that suggest this situation */
    val keywords: List<String>,
) {
    fun opener(name: String, hour: Int): String = "${TalkRules.greeting(name, hour)} $question"
}

object Scenarios {
    val OUTING = Scenario(
        id = "outing",
        titleKo = "가족끼리 놀러가요",
        emoji = "🎡",
        descKo = "놀이공원·동물원·공원 가는 길",
        situation = "The family is driving together to a fun place (an amusement park, a zoo or a park).",
        question = "We're going out with the family today! Where are we going?",
        targets = listOf("I'm so excited!", "Let's go together!", "I want to ride the carousel."),
        keywords = listOf("놀이공원", "랜드", "월드", "동물원", "공원", "아쿠아리움", "수목원", "목장"),
    )
    val TRIP = Scenario(
        id = "trip",
        titleKo = "여행을 가요",
        emoji = "🧳",
        descKo = "공항·바다·캠핑 가는 길",
        situation = "The family is going on a trip (to the airport, the beach or a campsite).",
        question = "We're going on a trip! What did you pack in your bag?",
        targets = listOf("I packed my teddy bear.", "Are we there yet?", "I want to see the sea."),
        keywords = listOf("공항", "호텔", "리조트", "해수욕장", "해변", "바다", "캠핑", "펜션", "휴게소", "터미널"),
    )
    val GRANDMA = Scenario(
        id = "grandma",
        titleKo = "할머니댁에 가요",
        emoji = "👵",
        descKo = "할머니·할아버지 만나러 가는 길",
        situation = "The family is driving to visit Grandma and Grandpa.",
        question = "We're going to Grandma's house! Do you miss Grandma?",
        targets = listOf("I miss Grandma.", "I love you, Grandma!", "Let's give Grandma a big hug."),
        keywords = listOf("할머니", "할아버지", "외가", "친가", "본가", "시댁", "처가"),
    )
    val MARKET = Scenario(
        id = "market",
        titleKo = "장보러 가요",
        emoji = "🛒",
        descKo = "마트·시장 가는 길",
        situation = "The family is driving to the supermarket to buy food.",
        question = "We're going to the supermarket! What do you want to buy?",
        targets = listOf("I want some apples.", "Can I have a snack?", "Let's buy some milk."),
        keywords = listOf("마트", "코스트코", "트레이더스", "시장", "백화점"),
    )
    val FRIEND = Scenario(
        id = "friend",
        titleKo = "친구를 만나러 가요",
        emoji = "🧒",
        descKo = "친구 집·놀이터·키즈카페 가는 길",
        situation = "The family is driving to meet the child's friend and play together.",
        question = "We're going to see your friend! What do you want to play?",
        targets = listOf("Let's play together!", "Can I play with you?", "Let's share."),
        keywords = listOf("놀이터", "키즈카페", "키즈 카페"),
    )
    val HOSPITAL = Scenario(
        id = "hospital",
        titleKo = "병원에 가요",
        emoji = "🏥",
        descKo = "소아과·치과 가는 길",
        situation = "The family is driving to see the doctor or the dentist. The child may feel a little sick or nervous: " +
            "that is expected here, so keep it light and reassuring, and do not ask about the illness in detail.",
        question = "We're going to see the doctor today. How do you feel?",
        targets = listOf("I feel okay.", "My tummy hurts.", "I'm brave!"),
        keywords = listOf("병원", "의원", "소아과", "치과", "이비인후과"),
    )

    val all: List<Scenario> = listOf(OUTING, TRIP, GRANDMA, MARKET, FRIEND, HOSPITAL)

    fun byId(id: String?): Scenario? = all.firstOrNull { it.id == id }

    /** The situation a destination name suggests ("우리 할머니댁" → grandma), or null. */
    fun forDestination(name: String?): Scenario? {
        if (name.isNullOrBlank()) return null
        return all.firstOrNull { s -> s.keywords.any { name.contains(it, ignoreCase = true) } }
    }
}
