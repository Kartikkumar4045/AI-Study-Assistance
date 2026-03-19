package com.example.aistudyassistance

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
import com.google.android.material.button.MaterialButton

class FlashcardPagerAdapter(
    private val flashcards: List<Flashcard>,
    private val revealedStates: MutableList<Boolean>,
    private val difficultyStates: MutableList<Int>,
    private val onCardTapped: (Int) -> Unit,
    private val onDifficultyClicked: (Int, Int) -> Unit,
    private val onFlipStateChanged: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<FlashcardPagerAdapter.FlashcardViewHolder>() {

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
            difficulty = difficultyStates[position],
            position = position,
            animateFlip = false
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
        holder.bind(
            flashcard = flashcards[position],
            revealed = revealedStates[position],
            difficulty = difficultyStates[position],
            position = position,
            animateFlip = animateFlip
        )
    }

    override fun getItemCount(): Int = flashcards.size

    fun isCardFlipping(position: Int): Boolean = position in flippingPositions

    fun toggleRevealState(position: Int): Boolean {
        if (position !in revealedStates.indices || isCardFlipping(position)) return false
        revealedStates[position] = !revealedStates[position]
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

    private fun showCardState(holder: FlashcardViewHolder, flashcard: Flashcard, revealed: Boolean) {
        holder.tvQuestion.text = flashcard.question
        holder.tvAnswer.text = flashcard.answer

        if (revealed) {
            stopHintPulse(holder.tvHint)
            holder.tvHint.visibility = View.GONE
            holder.tvAnswer.visibility = View.VISIBLE
            holder.layoutDifficulty.visibility = View.VISIBLE
        } else {
            holder.tvAnswer.visibility = View.GONE
            holder.layoutDifficulty.visibility = View.GONE
            holder.tvHint.visibility = View.VISIBLE
            startHintPulse(holder.tvHint)
        }
    }

    private fun applyDifficultyState(holder: FlashcardViewHolder, difficulty: Int) {
        val context = holder.itemView.context
        val buttons = listOf(holder.btnEasy, holder.btnMedium, holder.btnHard)

        buttons.forEach { button ->
            button.backgroundTintList = null
            button.alpha = 1f
            button.setTextColor(ContextCompat.getColor(context, R.color.text_main))
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
            duration = 180
            interpolator = DecelerateInterpolator()
        }

        val firstHalfScaleX = ObjectAnimator.ofFloat(card, View.SCALE_X, 1f, 0.95f).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
        }

        val firstHalfScaleY = ObjectAnimator.ofFloat(card, View.SCALE_Y, 1f, 0.95f).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
        }

        val secondHalfRotation = ObjectAnimator.ofFloat(card, View.ROTATION_Y, -90f, 0f).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
        }

        val secondHalfScaleX = ObjectAnimator.ofFloat(card, View.SCALE_X, 0.95f, 1f).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
        }

        val secondHalfScaleY = ObjectAnimator.ofFloat(card, View.SCALE_Y, 0.95f, 1f).apply {
            duration = 180
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
                showCardState(holder, flashcard, revealed)
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
        private val tvQuestionTitle: TextView = itemView.findViewById(R.id.tvQuestionTitle)
        val tvQuestion: TextView = itemView.findViewById(R.id.tvQuestion)
        val tvHint: TextView = itemView.findViewById(R.id.tvTapHint)
        val tvAnswer: TextView = itemView.findViewById(R.id.tvAnswer)
        val layoutDifficulty: View = itemView.findViewById(R.id.layoutDifficulty)
        val btnEasy: MaterialButton = itemView.findViewById(R.id.btnEasy)
        val btnMedium: MaterialButton = itemView.findViewById(R.id.btnMedium)
        val btnHard: MaterialButton = itemView.findViewById(R.id.btnHard)

        fun bind(
            flashcard: Flashcard,
            revealed: Boolean,
            difficulty: Int,
            position: Int,
            animateFlip: Boolean
        ) {
            tvQuestionTitle.text = "Question"
            cardFlashcard.cameraDistance = itemView.resources.displayMetrics.density * 8000f
            cardFlashcard.setOnClickListener {
                if (!isCardFlipping(position)) {
                    onCardTapped(position)
                }
            }

            btnEasy.setOnClickListener { onDifficultySelected(position, DIFFICULTY_EASY) }
            btnMedium.setOnClickListener { onDifficultySelected(position, DIFFICULTY_MEDIUM) }
            btnHard.setOnClickListener { onDifficultySelected(position, DIFFICULTY_HARD) }

            applyDifficultyState(this, difficulty)

            if (animateFlip) {
                flipCard(this, flashcard, revealed, position)
            } else {
                cardFlashcard.rotationY = 0f
                cardFlashcard.scaleX = 1f
                cardFlashcard.scaleY = 1f
                markFlipping(position, false, this)
                showCardState(this, flashcard, revealed)
            }
        }

        private fun onDifficultySelected(position: Int, difficulty: Int) {
            if (position !in difficultyStates.indices) return
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
    }
}


