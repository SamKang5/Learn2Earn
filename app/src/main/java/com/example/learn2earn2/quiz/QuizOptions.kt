package com.example.learn2earn2.quiz

object QuizOptions {
    val subjects = listOf(
        "Mathematics",
        "English",
        "Science",
        "Literature",
        "Foreign Language",
        "History",
        "Geography",
        "Physics",
        "Chemistry",
        "Biology",
        "Computer Science / ICT",
        "Art",
        "Music",
        "Physical Education",
        "Civics",
        "Economics",
        "Business Studies",
        "Design & Technology",
        "Philosophy",
        "Social Studies",
        "Psychology",
        "Drama",
        "Health Education",
        "Other"
    )

    val grades = listOf("Preschool") + (1..12).map { "Grade $it" }
}
