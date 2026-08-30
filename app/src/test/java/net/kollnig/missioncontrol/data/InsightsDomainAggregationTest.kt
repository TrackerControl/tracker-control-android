/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InsightsDomainAggregationTest {
    @Test
    fun reciprocalAliasesMergeIntoOneRow() {
        val result = aggregateDomainObservations(
            listOf(
                DomainObservation(setOf("alpha.example", "beta.example"), setOf(1)),
                DomainObservation(setOf("beta.example", "alpha.example"), setOf(2))
            )
        )

        assertEquals(
            listOf(AggregatedDomain("alpha.example or beta.example", 2)),
            result
        )
    }

    @Test
    fun overlappingAliasSetsStaySeparate() {
        val result = aggregateDomainObservations(
            listOf(
                DomainObservation(setOf("alpha.example", "beta.example"), setOf(1)),
                DomainObservation(setOf("beta.example", "gamma.example"), setOf(2)),
                DomainObservation(setOf("gamma.example", "delta.example"), setOf(3))
            )
        )

        assertEquals(
            listOf(
                AggregatedDomain("alpha.example or beta.example", 1),
                AggregatedDomain("beta.example or gamma.example", 1),
                AggregatedDomain("delta.example or gamma.example", 1)
            ),
            result
        )
    }

    @Test
    fun appUidsAreCountedOncePerConnectedGroup() {
        val result = aggregateDomainObservations(
            listOf(
                DomainObservation(setOf("alpha.example"), setOf(7, 8)),
                DomainObservation(setOf("alpha.example"), setOf(8, 9))
            )
        )

        assertEquals(3, result.single().appCount)
    }

    @Test
    fun distinctDomainsStaySeparate() {
        val result = aggregateDomainObservations(
            listOf(
                DomainObservation(setOf("z.example"), setOf(1, 2)),
                DomainObservation(setOf("a.example"), setOf(1, 2, 3))
            )
        )

        assertEquals(
            listOf(
                AggregatedDomain("a.example", 3),
                AggregatedDomain("z.example", 2)
            ),
            result
        )
    }

    @Test
    fun orderingIsDeterministicAndResultIsCapped() {
        val observations = (0 until 21).map { index ->
            DomainObservation(setOf("domain-${index.toString().padStart(2, '0')}.example"), setOf(1))
        }.reversed()

        val result = aggregateDomainObservations(observations)

        assertEquals(20, result.size)
        assertEquals("domain-00.example", result.first().label)
        assertEquals("domain-19.example", result.last().label)
    }
}
