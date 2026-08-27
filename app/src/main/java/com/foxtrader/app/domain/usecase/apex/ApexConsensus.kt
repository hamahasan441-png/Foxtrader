package com.foxtrader.app.domain.usecase.apex

import com.foxtrader.app.domain.usecase.apex.model.ApexVote

/**
 * Groups member votes into agreement clusters.
 *
 * This is separated from the engine because it carries the one rule that
 * decides whether an Apex marker can move after it is drawn, and that rule
 * deserves to be named and tested on its own rather than inferred from whatever
 * a synthetic series happens to produce.
 *
 * **The rule: a cluster ends the moment agreement is reached.** As soon as the
 * required number of distinct members have voted, the cluster is closed and
 * stamped on that bar. Votes that arrive afterwards — even from new members,
 * even inside the window — start the next cluster instead of joining this one.
 *
 * The alternative, taking every vote inside the window, is what an
 * "all available evidence" reading would suggest, and it repaints: a third
 * member confirming two bars later would move a marker that was already drawn,
 * because the cluster would then be stamped on the newer vote. A signal that
 * moves after the fact cannot be traded and cannot be honestly backtested, so
 * later agreement is treated as what it is — confirmation that arrived too
 * late to have been part of the decision.
 */
internal object ApexConsensus {

    /**
     * Cluster [votes] for a single direction, in ascending index order.
     *
     * One vote per member per cluster: a methodology firing twice inside the
     * window is one opinion repeated, not two that agree.
     */
    fun cluster(
        votes: List<ApexVote>,
        minAgreeingMembers: Int,
        agreementWindowBars: Int,
    ): List<List<ApexVote>> {
        if (votes.size < minAgreeingMembers) return emptyList()
        val ordered = votes.sortedWith(compareBy({ it.index }, { it.member.name }))
        val out = ArrayList<List<ApexVote>>()

        var start = 0
        while (start <= ordered.size - minAgreeingMembers) {
            val opening = ordered[start].index
            val contributing = LinkedHashMap<ApexMember, ApexVote>()
            var closedAt = -1

            for (i in start until ordered.size) {
                val vote = ordered[i]
                if (vote.index > opening + agreementWindowBars) break
                // Earliest vote per member: putIfAbsent keeps the first.
                contributing.putIfAbsent(vote.member, vote)
                if (contributing.size >= minAgreeingMembers) {
                    closedAt = i
                    break
                }
            }

            if (closedAt < 0) {
                start++
                continue
            }

            out += contributing.values.toList()
            // Everything up to and including the closing vote is spoken for.
            start = closedAt + 1
        }

        return out
    }
}
