package com.kartik.aistudyassistant.ui.flashcard

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.model.Flashcard
import com.google.android.material.button.MaterialButton

class FlashcardPagerAdapter(
    private val flashcards: List<Flashcard>,
    private val revealedStates: MutableList<Boolean>,
    private val answerRevealedStates: MutableList<Boolean>,
    private val difficultyStates: MutableList<Int>,
    private val isActiveRecallEnabled: () -> Boolean,
    private val onCardTapped: (Int) -> Unit,
    private val onShowAnswerClicked: (Int) -> Unit,
    private val onDifficultyClicked: (Int, Int) -> Unit,
    private val onAnswerRevealed: (Int) -> Unit,
    private val onFlipStateChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<FlashcardPagerAdapter.FlashcardViewHolder>() {

    private enum class StudyMode {
        QUICK_REVIEW,
        ACTIVE_RECALL
    }

    private val flippingPositions = mutableSetOf<Int>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FlashcardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_flashcard_page, parent, false)
        return FlashcardViewHolder(view)
    }

    override fun onBindViewHolder(holder: FlashcardViewHolder, position: Int) {
        holder.bind(
            flashcard = flashcards[position],
            revealed = revealedStates[position],
            answerRevealed = answerRevealedStates[position],
            difficulty = difficultyStates[position],
            position = position,
            animateFlip = false,
            animateReveal = false
        )
    }

    override fun onBindViewHolder(
        holder: FlashcardViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }

        val animateFlip = payloads.contains(PAYLOAD_FLIP)
        val animateReveal = payloads.contains(PAYLOAD_SHOW_ANSWER)
        holder.bind(
            flashcard = flashcards[position],
            revealed = revealedStates[position],
            answerRevealed = answerRevealedStates[position],
            difficulty = difficultyStates[position],
            position = position,
            animateFlip = animateFlip,
            animateReveal = animateReveal
        )
    }

    override fun getItemCount(): Int = flashcards.size

    fun isCardFlipping(position: Int): Boolean = position in flippingPositions

    fun toggleRevealState(position: Int): Boolean {
        if (position !in revealedStates.indices || isCardFlipping(position)) return false
        revealedStates[position] = !revealedStates[position]

        if (revealedStates[position] && !isActiveRecallEnabled()) {
            val wasRevealed = answerRevealedStates[position]
            answerRevealedStates[position] = true
            if (!wasRevealed) {
                onAnswerRevealed(position)
            }
        }

        notifyItemChanged(position, PAYLOAD_FLIP)
        return true
    }

    private fun markFlipping(position: Int, isFlipping: Boolean, holder: FlashcardViewHolder) {
        if (isFlipping) {
            flippingPositions.add(position)
        } else {
            flippingPositions.remove(position)
        }
        holder.cardFlashcard.isEnabled = !isFlipping
        onFlipStateChanged(position, isFlipping)
    }

    private fun showCardState(
        holder: FlashcardViewHolder,
        flashcard: Flashcard,
        revealed: Boolean,
        answerRevealed: Boolean
    ) {
        holder.tvQuestion.text = flashcard.question
        holder.tvAnswer.text = flashcard.answer

        val activeRecall = isActiveRecallEnabled()
        val studyMode = if (activeRecall) StudyMode.ACTIVE_RECALL else StudyMode.QUICK_REVIEW
        applyCardFaceVisualState(
            holder = holder,
            isFlipped = revealed,
            studyMode = studyMode,
            animateBackground = false
        )

        if (revealed) {
            stopHintPulse(holder.tvHint)
            holder.tvHint.visibility = View.GONE
            val shouldShowAnswer = !activeRecall || answerRevealed
            holder.tvThinkPrompt.visibility = if (activeRecall && !answerRevealed) View.VISIBLE else View.GONE
            holder.btnShowAnswer.visibility = if (activeRecall && !answerRevealed) View.VISIBLE else View.GONE
            holder.tvAnswer.visibility = if (shouldShowAnswer) View.VISIBLE else View.GONE
            holder.layoutDifficulty.visibility = if (shouldShowAnswer) View.VISIBLE else View.GONE
        } else {
            holder.tvThinkPrompt.visibility = View.GONE
            holder.btnShowAnswer.visibility = View.GONE
            holder.tvAnswer.visibility = View.GONE
            holder.layoutDifficulty.visibility = View.GONE
            holder.tvHint.visibility = View.VISIBLE
            startHintPulse(holder.tvHint)
        }
    }

    private fun resolveCardBackgroundColor(holder: FlashcardViewHolder, isFlipped: Boolean, studyMode: StudyMode): Int {
        val colorRes = when (studyMode) {
            StudyMode.QUICK_REVIEW -> if (isFlipped) {
                R.color.primary_light
            } else {
                R.color.card_bg
            }

            StudyMode.ACTIVE_RECALL -> if (isFlipped) {
                R.color.input_bg
            } else {
                R.color.card_bg
            }
        }
        return ContextCompat.getColor(holder.itemView.context, colorRes)
    }

    private fun applyCardFaceVisualState(
        holder: FlashcardViewHolder,
        isFlipped: Boolean,
        studyMode: StudyMode,
        animateBackground: Boolean
    ) {
        holder.tvQuestionTitle.text = if (isFlipped) "Answer" else "Question"

        val targetColor = resolveCardBackgroundColor(holder, isFlipped, studyMode)
        if (!animateBackground) {
            holder.cardFlashcard.setCardBackgroundColor(targetColor)
            return
        }

        val startColor = holder.cardFlashcard.cardBackgroundColor.defaultColor
        ValueAnimator.ofArgb(startColor, targetColor).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                holder.cardFlashcard.setCardBackgroundColor(animator.animatedValue as Int)
            }
            start()
        }
    }

    private fun animateAnswerReveal(holder: FlashcardViewHolder) {
        if (holder.tvAnswer.visibility != View.VISIBLE) return

        holder.tvAnswer.alpha = 0f
        holder.layoutDifficulty.alpha = 0f

        holder.tvAnswer.animate()
            .alpha(1f)
            .setDuration(220L)
            .start()

        holder.layoutDifficulty.animate()
            .alpha(1f)
            .setDuration(220L)
            .start()
    }

    private fun applyDifficultyState(holder: FlashcardViewHolder, difficulty: Int) {
        val context = holder.itemView.context
        val buttons = listOf(holder.btnEasy, holder.btnMedium, holder.btnHard)
        val defaultBg = ContextCompat.getColor(context, R.color.card_bg)
        val defaultText = ContextCompat.getColor(context, R.color.text_main)
        val defaultStroke = ContextCompat.getColor(context, R.color.divider)

        buttons.forEach { button ->
            button.backgroundTintList = ColorStateList.valueOf(defaultBg)
            button.strokeColor = ColorStateList.valueOf(defaultStroke)
            button.strokeWidth = 2
            button.alpha = 1f
            button.setTextColor(defaultText)
            button.isEnabled = difficulty == DIFFICULTY_NONE
        }

        if (difficulty in 0..2) {
            val selectedButton = buttons[difficulty]
            val selectedColor = when (difficulty) {
                DIFFICULTY_EASY -> ContextCompat.getColor(context, R.color.green)
                DIFFICULTY_MEDIUM -> ContextCompat.getColor(context, R.color.accent_orange)
                else -> ContextCompat.getColor(context, R.color.red)
            }

            selectedButton.backgroundTintList = ColorStateList.valueOf(selectedColor)
            selectedButton.strokeColor = ColorStateList.valueOf(selectedColor)
            selectedButton.alpha = 1f
            selectedButton.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (difficulty == DIFFICULTY_MEDIUM) R.color.black else R.color.white
                )
            )

            buttons.forEachIndexed { index, button ->
                if (index != difficulty) {
                    button.alpha = 0.55f
                }
            }
        }
    }

    private fun startHintPulse(view: TextView) {
        stopHintPulse(view)
        val pulse = ObjectAnimator.ofFloat(view, View.ALPHA, 0.45f, 1f).apply {
            duration = 650
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        view.tag = pulse
        pulse.start()
    }

    private fun stopHintPulse(view: TextView) {
        (view.tag as? ObjectAnimator)?.cancel()
        view.tag = null
        view.alpha = 1f
    }

    private fun flipCard(
        holder: FlashcardViewHolder,
        flashcard: Flashcard,
        revealed: Boolean,
        position: Int
    ) {
        val card = holder.cardFlashcard
        card.rotationY = 0f
        card.cameraDistance = card.resources.displayMetrics.density * 8000f

        val firstHalfRotation = ObjectAnimator.ofFloat(card, View.ROTATION_Y, 0f, 90f).apply {
            duration = 130
            interpolator = DecelerateInterpolator()
        }

        val firstHalfScaleX = ObjectAnimator.ofFloat(card, View.SCALE_X, 1f, 0.95f).apply {
            duration = 130
            interpolator = AccelerateDecelerateInterpolator()
        }

        val firstHalfScaleY = ObjectAnimator.ofFloat(card, View.SCALE_Y, 1f, 0.95f).apply {
            duration = 130
            interpolator = AccelerateDecelerateInterpolator()
        }

        val secondHalfRotation = ObjectAnimator.ofFloat(card, View.ROTATION_Y, -90f, 0f).apply {
            duration = 130
            interpolator = AccelerateDecelerateInterpolator()
        }

        val secondHalfScaleX = ObjectAnimator.ofFloat(card, View.SCALE_X, 0.95f, 1f).apply {
            duration = 130
            interpolator = AccelerateDecelerateInterpolator()
        }

        val secondHalfScaleY = ObjectAnimator.ofFloat(card, View.SCALE_Y, 0.95f, 1f).apply {
            duration = 130
            interpolator = AccelerateDecelerateInterpolator()
        }

        val firstHalf = AnimatorSet().apply {
            playTogether(firstHalfRotation, firstHalfScaleX, firstHalfScaleY)
        }

        val secondHalf = AnimatorSet().apply {
            playTogether(secondHalfRotation, secondHalfScaleX, secondHalfScaleY)
        }

        firstHalf.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {
                markFlipping(position, true, holder)
            }

            override fun onAnimationEnd(animation: Animator) {
                val answerRevealed = answerRevealedStates.getOrElse(position) { false }
                showCardState(holder, flashcard, revealed, answerRevealed)
                val studyMode = if (isActiveRecallEnabled()) StudyMode.ACTIVE_RECALL else StudyMode.QUICK_REVIEW
                applyCardFaceVisualState(
                    holder = holder,
                    isFlipped = revealed,
                    studyMode = studyMode,
                    animateBackground = true
                )
            }

            override fun onAnimationCancel(animation: Animator) = Unit
            override fun onAnimationRepeat(animation: Animator) = Unit
        })

        AnimatorSet().apply {
            playSequentially(firstHalf, secondHalf)
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) = Unit

                override fun onAnimationEnd(animation: Animator) {
                    markFlipping(position, false, holder)
                }

                override fun onAnimationCancel(animation: Animator) {
                    markFlipping(position, false, holder)
                }

                override fun onAnimationRepeat(animation: Animator) = Unit
            })
            start()
        }
    }

    inner class FlashcardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardFlashcard: CardView = itemView.findViewById(R.id.cardFlashcard)
        val tvQuestionTitle: TextView = itemView.findViewById(R.id.tvQuestionTitle)
        val tvQuestion: TextView = itemView.findViewById(R.id.tvQuestion)
        val tvHint: TextView = itemView.findViewById(R.id.tvTapHint)
        val tvThinkPrompt: TextView = itemView.findViewById(R.id.tvThinkPrompt)
        val btnShowAnswer: MaterialButton = itemView.findViewById(R.id.btnShowAnswer)
        val tvAnswer: TextView = itemView.findViewById(R.id.tvAnswer)
        val layoutDifficulty: View = itemView.findViewById(R.id.layoutDifficulty)
        val btnEasy: MaterialButton = itemView.findViewById(R.id.btnEasy)
        val btnMedium: MaterialButton = itemView.findViewById(R.id.btnMedium)
        val btnHard: MaterialButton = itemView.findViewById(R.id.btnHard)

        fun bind(
            flashcard: Flashcard,
            revealed: Boolean,
            answerRevealed: Boolean,
            difficulty: Int,
            position: Int,
            animateFlip: Boolean,
            animateReveal: Boolean
        ) {
            val isFlipped = revealed
            val studyMode = if (isActiveRecallEnabled()) StudyMode.ACTIVE_RECALL else StudyMode.QUICK_REVIEW
            cardFlashcard.cameraDistance = itemView.resources.displayMetrics.density * 8000f
            cardFlashcard.setOnClickListener {
                if (!isCardFlipping(position)) {
                    onCardTapped(position)
                }
            }

            btnShowAnswer.setOnClickListener {
                onShowAnswer(position)
            }
            btnShowAnswer.isEnabled = true
            btnShowAnswer.alpha = 1f

            btnEasy.setOnClickListener { onDifficultySelected(position, DIFFICULTY_EASY) }
            btnMedium.setOnClickListener { onDifficultySelected(position, DIFFICULTY_MEDIUM) }
            btnHard.setOnClickListener { onDifficultySelected(position, DIFFICULTY_HARD) }

            applyDifficultyState(this, difficulty)
            applyCardFaceVisualState(
                holder = this,
                isFlipped = isFlipped,
                studyMode = studyMode,
                animateBackground = false
            )

            if (animateFlip) {
                flipCard(this, flashcard, revealed, position)
            } else {
                cardFlashcard.rotationY = 0f
                cardFlashcard.scaleX = 1f
                cardFlashcard.scaleY = 1f
                markFlipping(position, false, this)
                showCardState(this, flashcard, revealed, answerRevealed)
                if (animateReveal) {
                    animateAnswerReveal(this)
                }
            }
        }

        private fun onShowAnswer(position: Int) {
            if (position !in answerRevealedStates.indices) return
            if (!revealedStates[position]) return
            if (answerRevealedStates[position]) return

            btnShowAnswer.isEnabled = false
            btnShowAnswer.alpha = 0.65f
            itemView.postDelayed({
                if (position !in answerRevealedStates.indices) return@postDelayed
                if (answerRevealedStates[position]) return@postDelayed

                answerRevealedStates[position] = true
                onShowAnswerClicked(position)
                onAnswerRevealed(position)
                notifyItemChanged(position, PAYLOAD_SHOW_ANSWER)
            }, 350L)
        }

        private fun onDifficultySelected(position: Int, difficulty: Int) {
            if (position !in difficultyStates.indices) return
            if (position !in answerRevealedStates.indices || !answerRevealedStates[position]) return
            if (difficultyStates[position] != DIFFICULTY_NONE) return

            difficultyStates[position] = difficulty
            notifyItemChanged(position)
            onDifficultyClicked(position, difficulty)
        }
    }

    companion object {
        const val DIFFICULTY_NONE = -1
        const val DIFFICULTY_EASY = 0
        const val DIFFICULTY_MEDIUM = 1
        const val DIFFICULTY_HARD = 2

        private const val PAYLOAD_FLIP = "payload_flip"
        private const val PAYLOAD_SHOW_ANSWER = "payload_show_answer"
    }
}



