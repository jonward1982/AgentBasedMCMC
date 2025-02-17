import lib.abstractAlgebra.*
import lib.sparseMatrix.GridMatrix
import lib.sparseVector.*
import java.lang.RuntimeException
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

//////////////////////////////////////////////////////////////////////////
// Represents a linear program in the form
// maximise CX
// subject to
// AX = B
// X >= 0
//
// Packed into a single matrix
//
// M = (A|B)
//     (C 0)
//////////////////////////////////////////////////////////////////////////
open class GridMapSimplex<T>(constraints: List<MutableConstraint<T>>, objective: SparseVector<T>, initialSolution: SparseVector<T>)
    : FieldOperators<T> by objective.operators
    where T: Number,
          T: Comparable<T>
{

    val firstSlackColumn: Int = constraints.numVars()

//    val entryMap: GridMap<T> = GridMap(constraints.size + 1, firstSlackColumn + constraints.numSlacks() + 1)

//    val M = GridMapMatrix(objective.operators, GridMap( constraints.size + 1, firstSlackColumn + constraints.numSlacks() + 1))

    val M = GridMatrix(constraints.size + 1, firstSlackColumn + constraints.numSlacks() + 1) {
        objective.new()
    }

    val basicColsByRow: IntArray = IntArray(nConstraints) { -1 } // -1 interpreted as artificial variable
    val basicRowsByCol = IntArray(nVariables) { -1 }


    val nVariables: Int
        get() = M.nCols - 1
    val nConstraints: Int
        get() = M.nRows - 1
    val objectiveRow: Int
        get() = M.nRows-1
    val bColumn: Int
        get() = M.nCols-1
    val B: MutableSparseVector<T> = M.getMutableColView(bColumn)
    val objective: MutableSparseVector<T> = M.getMutableRowView(objectiveRow)

//    class Matrix<T>(val mapData: GridMap<T>, val field: FieldOperators<T>): FieldOperators<T> by field {
//        val rows: List<MutableSparseVector<T>> = mapData.rows.map { it.asMutableVector() }
//        val columns: List<MutableSparseVector<T>> = mapData.columns.map { it.asMutableVector() }
//        val nRows: Int
//            get() = mapData.nRows
//        val nCols: Int
//            get() = mapData.nCols
//
//        operator fun get(row: Int, col: Int) = mapData[row,col]?:zero
//        operator fun set(row: Int, col: Int, value: T) { if(value.isZero()) mapData.remove(row,col) else mapData[row,col] = value }
//    }




    fun X(includeSlacks: Boolean=false): SparseVector<T> {
        val x = M.rows[0].new()
        for (i in basicColsByRow.indices) {
            val basicCol = basicColsByRow[i]
            if(includeSlacks || basicCol < firstSlackColumn) x[basicCol] = B[i] / M[i, basicCol]
        }
        return x
    }


//    constructor(
//        xCoefficients: SparseMatrix<T>,
//        constants: SparseVector<T>,
//        objective: SparseVector<T>)
//            : this(
//        GridMapMatrix(xCoefficients.operators,xCoefficients.nRows+1, xCoefficients.nCols+1)
//    ) {
//        xCoefficients.copyTo(M)
//        objective.copyTo(M.rows[objectiveRow])
//        constants.copyTo(M.columns[bColumn])
//
//        // Find initial positive solution
////        val initialSolution = xCoefficients
////            .IPsolve(constants, emptyMap<Int,T>().asVector(xCoefficients.operators), "==")
////            .asList()
////            .mapIndexedNotNull { index, value ->
////                if(value != 0.0) index else null
////            }
////        pivotColumns(initialSolution)
//        findInitialSolution()
//    }

    constructor(constraints: List<MutableConstraint<T>>, objective: SparseVector<T>) : this(
        constraints,
        objective,
        with(objective.operators) {
            ORTools.GlopSolve(constraints)
                .withIndex()
                .filter { it.value != 0.0 }
                .associate { Pair(it.index, it.value.toField()) }
                .asVector()
        }
    ) {
        println(B)
        assert(isPrimalFeasible())
    }


    // initialBasicVars are variables that must be in the initial pivot state, but not necessarily a full
    // piot state.
    init {
        println("Transferring constraints to simplex tableau...")
        var nextSlackVar = firstSlackColumn
        val initialNonZeroColumns = ArrayList(initialSolution.nonZeroEntries.keys)
        constraints.forEachIndexed { i, constraint ->
            val slackness = constraint.slackness(initialSolution)
            if(slackness < -zero || (constraint.relation == "==" && !slackness.isZero())) println("WARNING: initial solution is not feasible: Slackness $slackness")
            if(!slackness.isZero()) initialNonZeroColumns.add(nextSlackVar)
            if (constraint.constant >= zero) {
                constraint.coefficients.forEach { (j, x) -> M[i, j] = x }
                B[i] = constraint.constant
                when (constraint.relation) {
                    "<=" -> { basicColsByRow[i] = nextSlackVar; M[i, nextSlackVar++] = one }
                    ">=" -> { M[i, nextSlackVar++] = -one }
                }
            } else {
                constraint.coefficients.forEach { (j, x) -> M[i, j] = -x }
                B[i] = -constraint.constant
                when (constraint.relation) {
                    "<=" -> { M[i, nextSlackVar++] = -one }
                    ">=" -> { basicColsByRow[i] = nextSlackVar; M[i, nextSlackVar++] = one }
                }
            }
        }
        objective.nonZeroEntries.forEach { (j,x) ->
            if(j >= firstSlackColumn) throw(IllegalArgumentException("objective contains an unconstrained variable"))
            this.objective[j] = x
        }

        println("Pivoting in initial solution")
        initialPivot(initialNonZeroColumns)
//        initialFeasiblePivot(initialBasicVars)
        println("Done")
    }


    fun pivotToInitialSolution() {
        val initialSolution = ORTools.GlopSolve(M)
        println("Pivoting-in initial solution")
        initialPivot(initialSolution.indices.filter { initialSolution[it] != 0.0 })
        println("Initial solution ${X()}")
    }


    fun pivotToInitialIntegerSolution() {
        println("Finding initial integer solution")
        val initialSolution = ORTools.IntegerSolve(M)
        println("Pivoting-in initial solution")
        initialPivot(initialSolution.indices.filter { initialSolution[it] != 0.0 })
        println("Initial solution ${X()}")
    }


    // Find an initial positive pivot state from a raw set of constraints
    // using artificial variables.
    // First look for any columns that are already potentially basic
    // then pivot to reduce negativity until non-negative solution is found
    //
    // Returns true if a positive solution exists
    fun pivotToInitialSolutionWithoutORTools(): Boolean {
        println("Finding initial solution")
//        // ensure all B_i are positive
//        for(i in constraintIndices) {
//            if(B[i] < zero) M.rows[i] *= -one
//        }
//        // Identify already pivoted columns
//        for(j in variableIndices) {
//            val colSize = M.columns[j].nonZeroEntries.size
//            if(colSize == 1 || (colSize == 2 && !objective[j].isZero())) {
//                M.columns[j].nonZeroEntries.entries
//                    .find { it.key != objectiveRow && it.value > zero }
//                    ?.also { (i, Mij) ->
//                        basicColsByRow[i] = j
//                        M.rows[i] /= Mij
//                    }
//            }
//        }
        // create new objective
        val originalObjective = ArrayList(M.rows[objectiveRow].nonZeroEntries.entries)
        objective.setToZero()
        for(i in 0 until nConstraints) {
            if(basicColsByRow[i] == -1) objective -= M.rows[i]
        }
        println("Doing phase I minimisation")
        var success = greedyMinimise()
        println("Done")
        if(success) {
            // pivot in any remaining (degenerate) rows
            for(i in 0 until nConstraints) {
                if(basicColsByRow[i] == -1) {
                    if(B[i] != zero) success = false
                    M.rows[i].nonZeroEntries.entries.find { it.key != bColumn }?.also { (j,_) ->
                        pivot(i,j)
                    }
                }
            }

        }
        // restore original objective
        setObjective(originalObjective)
        return success
    }


    fun setObjective(entries: Iterable<Map.Entry<Int,T>>) {
        objective.setToZero()
        entries.forEach { (j, Cj) ->
            objective[j] = Cj
        }
        // update objective for current pivot state
        for(i in 0 until nConstraints) {
            val j = basicColsByRow[i]
            if(objective[j] != zero) {
                objective -= M.rows[i]*objective[j] // TODO: weighted plus assign
            }
        }
    }


    // Pivots until all elements of X are non-negative.
    // Alternatively, X-negativity (minus the sum of values of
    // negative elements of X) is zero.
    //
    // Does this by repeatedly pivoting on the column
    // whose sum over elements in rows with negative B is most negative
    // and the row that first forces B_i to zero (from either side) as the value of this
    // column increases.
    // Pivoting stops when no column has a negative sum over elements in B-negative rows.
    // At this point, either X is non-negative or no non-negative solution exists
    // (since if no column has a negative sum then no pivot decreases X negativity,
    // so X negativity must be minimised, by the Simplex algorithm)
    // N.B. Pivot-loops are theoretically possible but rarely
    // encountered in practice
    //
    // returns true if a non-negative solution is found.
    fun pivotOutNegatives(): Boolean {
        var negativeRows: List<Int>
        do {
            negativeRows = B.nonZeroEntries
                .entries
                .filter { it.value < operators.zero && it.key < objectiveRow }
                .map { it.key }
            val sumOfNegativeRows = MutableMapVector(operators, HashMap())
            negativeRows.forEach {
                sumOfNegativeRows += M.rows[it]
            }
            sumOfNegativeRows.nonZeroEntries.remove(bColumn)
            val (pivotColumn, bNegativeSum) = sumOfNegativeRows.nonZeroEntries
                .minBy { it.value }
                ?: AbstractMap.SimpleEntry<Int, T>(0, zero)
            if (bNegativeSum < zero) {
                // pivot on the first row to reach zero from either +ve or -ve side
                // this guarantees that negativity is non-increasing
                var dXjmax: T? = null
                var pivotRow: Int? = null
                M.columns[pivotColumn].nonZeroEntries.forEach { (i, Mij) ->
                    if (i != objectiveRow) {
                        val dXji = B[i] / Mij
                        if (dXji >= zero && dXji <= dXjmax ?: dXji) {
                            dXjmax = dXji
                            pivotRow = i
                        }
                    }
                }
                pivot(pivotRow!!, pivotColumn) // pivotRow cannot be null if bNegativeSum < 0
            }
        } while (bNegativeSum < operators.zero)
        return negativeRows.isEmpty()
    }

    // perform simplex algorithm, pivoting on the column
    // with the most negative value in the objective row
    // to find the solution that minimises the objective.
    // Returns true if the minimum was found, otherwise
    // the solution is unbounded or no solution exists.
    fun greedyMinimise(): Boolean {
        while (greedyPivot()) {
//            println(i++)
//            println(M)
        }
        return objective.nonZeroEntries.none { it.value < -zero }
    }


    // perform simplex algorithm with Bland(77) ordering
    // to find the solution that minimises the objective.
    // Returns true if the minimum was found. Otherwise
    // the solution is unbounded or no solution exists.
    fun bland77minimise(): Boolean {
        while (bland77Pivot()) {
//            println(M)
        }
        return objective.nonZeroEntries.none { it.value < -zero }
    }


    // Perform the next pivot according to the ordering
    // defined in the first algorithm in Bland(77)
    // i.e. choose the column with the lowest index from among those
    // that have -ve coefficient in the objective.
    // If no columns have -ve coefficient, we have reached a minimum.
    // Then choose the row whose basic variable has the lowest (column) index
    // from among the columns with +ve coefficient.
    // If no columns have +ve coefficient, then the solution is unbounded
    // Returns true if the pivot was performed
    private fun bland77Pivot(): Boolean {
        val pivotCol = objective.nonZeroEntries.asSequence()
            .mapNotNull { if(it.value < -zero && it.key != bColumn) it.key else null }
            .min() // TODO: Could be made faster by keeping the objective row in an ordered tree
            ?:return false
        val pivotRow = pivotableRows(pivotCol, false)
            .minBy { basicColsByRow[it] }
            ?:return false
        pivot(pivotRow, pivotCol)
        return true
    }


    // Perform the next pivot on the column with the
    // most negative value
    // If no columns have -ve coefficient, we have reached a minimum.
    // Then choose the row whose basic variable has the lowest (column) index
    // from among the columns with +ve coefficient.
    // If no columns have +ve coefficient, then the solution is unbounded
    // Returns true if the pivot was performed
    private fun greedyPivot(): Boolean {
        val pivotCol = objective.nonZeroEntries.asSequence()
            .filter { it.value < -zero && it.key != bColumn }
            .minBy { it.value }
            ?.key
            ?:return false
        val pivotRow = pivotableRows(pivotCol, false)
            .firstOrNull()
            ?:return false
        pivot(pivotRow, pivotCol)
        return true
    }


    // pivots in all columns in the supplied list
    // then greedily pivots in any remaining rows
    fun initialPivot(columnsToInclude: List<Int>) {
        val canPivotRow = Array(basicColsByRow.size) { true }
        for(j in columnsToInclude) {
            if(!isBasicColumn(j)) {
                val pivotRow =
                    M.columns[j].nonZeroEntries
                        .filter { it.key != objectiveRow && canPivotRow[it.key] }
                        .minBy { M.rows[it.key].nonZeroEntries.size }
//                        .minBy { it.value.absoluteValue }
                        ?.key
                        ?:throw(RuntimeException("Can't pivot to solution. Must not be an extreme solution."))
                pivot(pivotRow, j)
                canPivotRow[pivotRow] = false
            } else {
                canPivotRow[M.columns[j].nonZeroEntries.keys.find { it != objectiveRow }!!] = false
            }
        }
        pivotOutAllArtificialVariables()
        columnsToInclude.forEach { assert( isBasicColumn(it) ) }
        assert(basicColsByRow.all { it != -1 })
    }


    fun initialFeasiblePivot(columnsToInclude: List<Int>) {
        val canPivotRow = Array(basicColsByRow.size) { true }
        val toPivotIn = LinkedList(columnsToInclude)
        toPivotIn.removeIf { j ->
            if(isBasicColumn(j)) {
                canPivotRow[M.columns[j].nonZeroEntries.keys.find { it != objectiveRow }!!] = false
                true
            } else {
                false
            }
        }
        while(toPivotIn.isNotEmpty()) {
            toPivotIn.removeIf { j ->
                pivotableRows(j, true)
                    .firstOrNull { canPivotRow[it] }
                    ?.let { i ->
                        pivot(i, j)
                        canPivotRow[i] = false
                        true
                    }
                    ?:false
            }
        }
        pivotOutAllArtificialVariables()
    }


    // pivots in any rows that aren't already pivoted in
    // using a greedy algorithm:
    // for each row in turn, choose to pivot on the column with smallest
    // sparse size among all that result in a non-negative pivot value
    fun pivotOutAllArtificialVariables() {
        for(pivotRow in basicColsByRow.indices) {
            if(basicColsByRow[pivotRow] == -1) {
                val Bi = B[pivotRow]
                val pivotCol = M.rows[pivotRow].nonZeroEntries
                    .filter { it.key != bColumn &&  Bi/it.value >= zero }
                    .minBy { M.columns[it.key].nonZeroEntries.size }
                    ?.key
                    ?:throw(RuntimeException("No elements to pivot on."))
                pivot(pivotRow, pivotCol)
            }
        }
    }

    // true if this column is currently a basic variable
    fun isBasicColumn(j: Int): Boolean {
        return basicRowsByCol[j] != -1
//        val col = M.columns[j]
//        return if (col.nonZeroEntries.size > 2) {
//            false
//        } else {
//            col.nonZeroEntries.keys
//                .find { it != objectiveRow }
//                ?.let { basicColsByRow[it] == j }
//                ?:false
//        }
    }


    // Does the actual pivoting of M
    // For a pivot at point (i,j), the k'th row of M and B is updated according to
    // M_k' = (M_ij*M_k - M_kj*M_i)/G
    // where G is the greatest common divisor of M_ij and M_kj
    // if the pivot point is -ve, multiplies the pivot row by -1 to make it positive
    inline fun pivot(point: PivotPoint) = pivot(point.row, point.col)
    fun pivot(i: Int, j: Int) {
//        assert(i>=0)
//        assert(i < M.nRows-1)
//        assert(j>=0)
//        assert(j < M.nCols-1)
        var Mij = M[i,j]

        M.mapAssignRow(i) { it/Mij }
        val colEntries = M.columns[j].nonZeroEntries
            .mapNotNull {
                if(it.key != i) AbstractMap.SimpleEntry(it.key, it.value) else null
            }

        for(rowEntry in M.rows[i].nonZeroEntries) {
            for(colEntry in colEntries) {
//                val newVal = (entryMap[colEntry.key, rowEntry.key]?:zero) - rowEntry.value * colEntry.value
//                if (newVal.isZero()) {
//                    entryMap.remove(colEntry.key, rowEntry.key)
//                } else {
//                    entryMap[colEntry.key, rowEntry.key] = newVal
//                }
                M[colEntry.key, rowEntry.key] -= rowEntry.value * colEntry.value
//                val dotProd = rowEntry.value * colEntry.value
//                M.columns[rowEntry.key].nonZeroEntries.compute(colEntry.key) { col, oldVal ->
//                    val newVal = (oldVal?:zero) - dotProd
//                    if(newVal.isZero()) null else newVal
//                }
            }
        }

        val leavingColumn = basicColsByRow[i]
        if(leavingColumn != -1) basicRowsByCol[leavingColumn] = -1
        basicColsByRow[i] = j
        basicRowsByCol[j] = i
    }

    fun rowPivot(i: Int, j: Int) {
        var Mij = M[i,j]

        val rowIndices = M.rows[i].nonZeroEntries.keys.toIntArray()
        val rowValues = rowIndices.map { k ->
            val newRowVal = M[i,k] / Mij
            M[i,k] = newRowVal
            newRowVal
        }

        for(row in 0 until nConstraints) {
            val rowWeight = M[row,j]
            if(row != i && rowWeight != 0.0) {
                for(rowEntry in rowIndices.indices) {
                    M[row, rowIndices[rowEntry]] -= rowValues[rowEntry] * rowWeight
                }
            }
        }

        val leavingColumn = basicColsByRow[i]
        if(leavingColumn != -1) basicRowsByCol[leavingColumn] = -1
        basicColsByRow[i] = j
        basicRowsByCol[j] = i
    }


    // Returns the rows in column j that are
    // pivot points that maintain a positive solution
    fun pivotableRows(j: Int, allowPivotsOnNegativeElements: Boolean): List<Int> {
        if(isBasicColumn(j)) return emptyList()

        var dXjmax: T? = null
        val limits = M.columns[j].nonZeroEntries.mapNotNull { (i, Mij) ->
            if(i != objectiveRow && (Mij > zero || (allowPivotsOnNegativeElements && B[i] <= zero))) {
                val dXji = B[i]/Mij
                if (dXji <= dXjmax?:dXji) dXjmax = dXji
                Pair(i, dXji)
            } else null
        }

        return limits
            .filter { it.second as Number == dXjmax }
            .map { it.first }
    }


    // maximum +ve value this column can take in a pivot without forcing -ve solution
    // null if there is no limit.
    fun columnPivotLimit(j: Int): T? {
        var dXjmax: T? = null
        M.columns[j].nonZeroEntries.forEach { (i, Mij) ->
            if(i != objectiveRow && Mij > zero) {
                val dXji = B[i]/Mij
                if (dXji <= dXjmax?:dXji) dXjmax = dXji
            }
        }
        return dXjmax
    }


    // Returns a list of all pivot points that maintain primal feasibility
    fun allPositivePivotPoints(): List<PivotPoint> {
        return (0 until M.nCols).asSequence()
            .filter { it != bColumn }
            .flatMap { j ->
                pivotableRows(j,false).asSequence()
                    .map { i ->
                        PivotPoint(i,j)
                    }
            }.toList()
    }

//    data class PivotPointsByDegeneracy(val degeneratePivots: List<PivotPoint>, val nonDegeneratePivots: List<PivotPoint>)
//    fun allPositivePivotPointsByDegeneracy(): PivotPointsByDegeneracy {
//        val degeneratePivots = ArrayList<PivotPoint>()
//        val nonDegeneratePivots = ArrayList<PivotPoint>()
////        return (0 until M.nCols) asSequence()
////            .filter { it != bColumn }
////            .flatMap { j ->
////                pivotableRows(j).asSequence()
////                    .map { i ->
////                        PivotPoint(i,j)
////                    }
////            }.toList()
//    }

    fun isDegenerate(pivot: PivotPoint): Boolean = B[pivot.row].isZero()


    // returns all pivots that maintain a positive solution
    // and that do not have a zero in the B column
    // (i.e. that a pivot here will change the solution)
    fun allNonDegeneratePivots(): List<PivotPoint> {
        return (0 until M.nCols).asSequence()
            .filter { it != bColumn }
            .flatMap { j ->
                pivotableRows(j,false).asSequence()
                    .filter { i -> !B[i].isZero() }
                    .map { i ->
                        PivotPoint(i,j)
                    }
            }.toList()

    }

    // returns all pivots that maintain a positive solution
    // and that have a zero in the B column
    // (i.e. that a pivot here will not change the solution)
    fun allDegeneratePivots(): List<PivotPoint> {
        return (0 until bColumn).asSequence()
            .flatMap { j ->
                pivotableRows(j,false).asSequence()
                    .filter { i -> B[i].isZero() }
                    .map { i ->
                        PivotPoint(i,j)
                    }
            }.toList()
    }




    fun postPivotB(pivot: PivotPoint): SparseVector<T> {
        val Bpiv = B[pivot.row]/M[pivot.row,pivot.col]
        val Bprime = B.toMutableSparseVector()
        Bprime.weightedPlusAssign(M.columns[pivot.col], -Bpiv)
        Bprime[pivot.row] = Bpiv
        return Bprime
    }



    fun T.roundToInt(): Int = this.toDouble().roundToInt()

    fun testSolution(x: DoubleArray) {
        println("Testing solution")
        for(i in 0 until objectiveRow) {
            var Bi = 0.0
            for(j in x.indices) {
                Bi += M[i,j].toDouble()*x[j]
            }
            assert((B[i].toDouble() - Bi).absoluteValue < 1e-6)
        }
        println("Done")
    }

    fun isPrimalFeasible(): Boolean {
        return B.nonZeroEntries.values.all { it > zero }
    }

    fun isFullyPivoted(): Boolean {
        return basicColsByRow.all { it != -1 }
    }

    fun isValid(): Boolean {
        return isPrimalFeasible() && isFullyPivoted()
    }

    fun degeneracy(): Int {
        return basicColsByRow.size - B.nonZeroEntries.size
    }


    fun degeneracyRatio(): Double {
        return (basicColsByRow.size - B.nonZeroEntries.size).toDouble()/basicColsByRow.size
    }

}

