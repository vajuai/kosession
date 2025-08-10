/*
 * Copyright 2024-2025 Embabel Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.embabel.template.agent

import com.embabel.agent.api.annotation.*
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.create
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.Auto
import com.embabel.common.core.types.Timestamped
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*=====================================================================
  1️⃣ 统一资源（Persona、LlmOptions、日期格式）放在单例 object 中
=====================================================================*/
object AgentResources {
    // ---------- Personas ----------
    val STORY_TELLER = Persona(
        name = "Roald Dahl",
        persona = "A creative storyteller who loves to weave imaginative tales that are a bit unconventional",
        voice = "Quirky",
        objective = "Create memorable stories that captivate the reader's imagination.",
        role = "Storyteller"
    )
    val REVIEWER = Persona(
        name = "Media Book Review",
        persona = "New York Times Book Reviewer",
        voice = "Professional and insightful",
        objective = "Help guide readers toward good stories",
        role = "Reviewer"
    )
    val APPROVER = Persona(
        name = "Media Book Approver",
        persona = "New York Times Book Approver",
        voice = "Professional and insightful",
        objective = "Final decision maker to approve or reject stories",
        role = "Approver"
    )

    // ---------- LLM Options ----------
    val STORY_OPTIONS = LlmOptions(criteria = Auto, temperature = 0.9)
    val DEFAULT_OPTIONS = LlmOptions(criteria = Auto)

    // ---------- 日期格式 ----------
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy")
    fun formatNow(): String = Instant.now()
        .atZone(ZoneId.systemDefault())
        .format(DATE_FORMAT)
}

/*=====================================================================
  2️⃣ 给 OperationContext 加一个 DSL‑style 扩展函数，统一 Prompt 调用
=====================================================================*/
inline fun <reified R : Any> OperationContext.runPrompt(
    llmOptions: LlmOptions = AgentResources.DEFAULT_OPTIONS,
    persona: Persona,
    prompt: String
): R = this.promptRunner(llmOptions)
    .withPromptContributor(persona)
    .create<R>(prompt.trimIndent())

/*=====================================================================
  3️⃣ 抽取统一的 BaseContent（实现 HasContent + Timestamped）
=====================================================================*/
abstract class BaseContent(
    private val builder: (Instant) -> String
) : HasContent, Timestamped {

    override val timestamp: Instant
        get() = Instant.now()

    override val content: String
        get() = builder(timestamp)
}

/*=====================================================================
  4️⃣ 业务 DTO（仅保留业务字段，content 与 timestamp 交给 BaseContent）
=====================================================================*/
data class Story(val text: String)

data class RevisedStory(val text: String)

/*---------- ReviewedStory ----------*/
data class ReviewedStory(
    val story: Story,
    val comments: String
) : BaseContent({ ts ->
    """
    # Story
    ${story.text}

    # Review comments
    $comments

    ${AgentResources.REVIEWER.name} : ${ts.atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy"))}
    """.trimIndent()
})

/*---------- FinalStory ----------*/
data class FinalStory(
    val story: RevisedStory,
    val approved: String
) : BaseContent({ ts ->
    """
    # Story
    ${story.text}
    # Approval comments
    $approved

    ${AgentResources.APPROVER.name} : ${ts.atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy"))}
    """.trimIndent()
})

/*=====================================================================
  5️⃣ Agent 本体：所有 @Action 只保留业务流程，日志抽离
=====================================================================*/
@Agent(
    description = "Generate a story, review it, revise it, and finally approve it.",
    name = "Write, Review and Approve Story"
)
@Profile("!test")
class WriteReviewAndApproveAgent(
    @param:Value("\${storyWordCount:100}") private val storyWordCount: Int,
    @param:Value("\${reviewWordCount:100}") private val reviewWordCount: Int,
) {

    /*--------------------------------------------------------------
      1️⃣ Craft the initial story
    --------------------------------------------------------------*/
    @Action
    fun craftStory(userInput: UserInput, ctx: OperationContext): Story =
        ctx.runPrompt<Story>(
            llmOptions = AgentResources.STORY_OPTIONS,
            persona = AgentResources.STORY_TELLER,
            prompt = """
                Craft a short story in $storyWordCount words or less.
                The story should be engaging and imaginative.
                Use the user's input as inspiration if possible.
                If the user has provided a name, include it in the story.

                # User input
                ${userInput.content}
            """
        ).also { logStep("Crafted Story", it.text) }

    /*--------------------------------------------------------------
      2️⃣ Review the story
    --------------------------------------------------------------*/
    @Action
    fun reviewStory(story: Story, ctx: OperationContext): ReviewedStory =
        ctx.runPrompt<ReviewedStory>(
            persona = AgentResources.REVIEWER,
            prompt = """
                You will be given a short story to review.
                Review it in $reviewWordCount words or less.
                Include the reviewer persona: ${AgentResources.REVIEWER.name}.

                # Story
                ${story.text}
            """
        ).also { logStep("Reviewed Story", it.comments) }

    /*--------------------------------------------------------------
      3️⃣ Revise according to the review
    --------------------------------------------------------------*/
    @Action
    fun reviseStory(reviewed: ReviewedStory, ctx: OperationContext): RevisedStory =
        ctx.runPrompt<RevisedStory>(
            persona = AgentResources.STORY_TELLER,
            prompt = """
                Revise the short story (max $storyWordCount words) based on the review comments.

                # Comments
                ${reviewed.comments}
                # Original Story
                ${reviewed.story.text}
            """
        ).also { logStep("Revised Story", it.text) }

    /*--------------------------------------------------------------
      4️⃣ Approve the revised story (final output)
    --------------------------------------------------------------*/
    @AchievesGoal(
        description = "The user has been greeted",
        export = Export(remote = true, name = "writeReviewAndApproveStory")
    )
    @Action
    fun approveStory(revised: RevisedStory, ctx: OperationContext): FinalStory =
        ctx.runPrompt<String>(
            persona = AgentResources.APPROVER,
            prompt = """
                You will be given a short story to approve.
                Review it in $reviewWordCount words or less.
                Consider whether the story is engaging, imaginative, well‑written,
                and appropriate to the original user input.

                # Story
                ${revised.text}
            """
        ).let { approvalText ->
            FinalStory(
                story = revised,
                approved = "${AgentResources.APPROVER.name}: $approvalText"
            )
        }.also { logStep("Final Approved Story", it.content) }

    /*--------------------------------------------------------------
      简单日志工具 – 生产环境可替换为 SLF4J / Logback
    --------------------------------------------------------------*/
    private fun logStep(step: String, payload: String) {
        // 这里保持最原始的 console 输出，真实项目中请改成 logger.info(...)
        println("=== $step ===\n$payload\n")
    }
}
