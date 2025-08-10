package com.embabel.template.agent

import com.embabel.agent.api.annotation.*
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.create
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria.Companion.Auto
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(Word2StructAgent::class.java)

private val PROMPT_TEMPLATE = """
    you are good at structure data extracting, based on the input text from user, you need to professionally
    extract the following information:
    #Content:
    {{content}}
    
    #Entities
    
    #Verbs
""".trimIndent()

data class StructuredData(
    val content: String,
    val entities: List<String>,
    val verbs: List<String>
)

@Agent(
    description = "Generate structured data from a simple text input",
    name = "Convert Text to Structured Data"
)
@Profile("!test")
class Word2StructAgent(
    @param:Value("\${word2StructWordCount:100}") private val wordCount: Int,
    private val llmConfig: LlmConfig = LlmConfig()   // 默认构造，亦可注入
) {

    @AchievesGoal(
        description = "The structured data has been converted",
        export = Export(remote = true, name = "word2StructAgent")
    )
    @Action
    fun convert2struct(userInput: UserInput, context: OperationContext): StructuredData =
        context.promptRunner()
            .withLlm(llmConfig.options)
            .withPromptContributor(llmConfig.persona)
            .create<StructuredData>(
                PROMPT_TEMPLATE.replace(
                    "{{content}}",
                    userInput.content
                        .split("\\s+")
                        .take(wordCount)
                        .joinToString(" ")
                )
            )
            .also { log.debug("Step 1: Structured Data → {}", it.content) }
}

/** 只放一次配置，供多 Agent 共享 */
@Component
class LlmConfig {
    val options = LlmOptions(criteria = Auto, temperature = 0.3)
    val persona = Persona(
        name = "User",
        persona = "a helper to testing",
        voice = "accuracy",
        objective = "analyze the result"
    )
}
