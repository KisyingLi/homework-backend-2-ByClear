
# 📬 Mission Center Service – Backend Homework

This is a technical assignment for backend engineer candidates. You are expected to build a RESTful mission center service using **Spring Boot**, integrating **MySQL**, **Redis**, and **RocketMQ**.


---

## 🎯 Objective

You are required to implement a 30-day mission system for new users.  
The goal is to track user activity and automatically distribute rewards once specific missions are completed.  
All user gameplay actions are triggered via an API and stored in the database.  
The system should be designed with performance, scalability, and clean architecture in mind.  
In addition, the system must include a Mission Center where users can view the current progress of each mission in real-time.

---

## 🧰 Tech Requirements

You **must use** the following technologies:

- **Java 21+**
- **Spring Boot**
- **MySQL** (for persistence)
- **Redis** (for caching)
- **RocketMQ** (for event messaging)

You may use starter dependencies such as:
- Spring Web
- Spring Data JPA
- Spring Cache
- RocketMQ Spring Boot Starter

---

## 🔧 Features to Implement

### Implement a RESTful backend service that supports the following features:

### 1️⃣  There are three missions to complete 
1. Log in for three consecutive days.
2. Launch at least three different games.
3. Play at least three game sessions with a combined score of over 1,000 points.

Once all missions are completed, the user should receive a 777-point reward.  
The system should expose a Mission Center view that returns the user’s current mission status and progress.  

### 2️⃣  You are required to implement at least the following APIs (additional APIs are welcome):
- POST /login – Simulate a user login event
- POST /launchGame – Record a game launch event
- POST /play – Record a gameplay session
- GET /missions – Get the missions list including progress.

### 3️⃣ You are required to implement at least the following database tables:
- **users** – User information
- **games** – Game metadata
- **games_play_record** – Game play records
- **missions** – Track user mission progress and reward status

### 🟩 You are encouraged to design additional tables or services as needed to support a clean and maintainable architecture.

⸻

🧪 Bonus (Optional)
- Use Spring Cache abstraction or RedisTemplate encapsulation
- Apply proper error handling with meaningful status codes
- Define your own DTO and message format for RocketMQ
- Use consistent and modular code structure (controller, service, repository, config, etc.)
- Test case coverage: as much as possible

⸻

🐳 Environment Setup

Use the provided docker-compose.yaml file to start required services:

Service	Port  
MySQL	3306  
Redis	6379  
RocketMQ Namesrv	9876  
RocketMQ Broker	10911  
RocketMQ Console	8088  

To start the services:

```commandline
docker-compose up -d
```

MySQL credentials:
- User: taskuser
- Password: taskpass
- Database: taskdb

You may edit init.sql to create required tables automatically.

⸻

🚀 Getting Started

To run the application:

./mvn spring-boot:run

Make sure to update your application.yml with the proper connections for:
- spring.datasource.url
- spring.redis.host
- rocketmq.name-server

⸻

📤 Submission

Please submit a `public Github repository` that includes:
- ✅ Complete and executable source code
- ✅ README.md (this file)
- ✅ Any necessary setup or data scripts please add them in HELP.md
- ✅ Optional: Postman collection or curl samples  

⸻

📌 Notes
- Focus on API correctness, basic error handling, and proper use of each technology
- You may use tools like Vibe Coding / ChatGPT to assist, but please write and understand your own code
- The expected time to complete is around 3 hours

Good luck!

---
