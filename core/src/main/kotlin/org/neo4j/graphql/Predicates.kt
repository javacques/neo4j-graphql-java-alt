package org.neo4j.graphql

import graphql.language.*
import graphql.language.TypeDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.Predicates.OngoingListBasedPredicateFunction
import org.slf4j.LoggerFactory

typealias CypherDSL = org.neo4j.cypherdsl.core.Cypher

private fun createArrayPredicate(factory: (SymbolicName) -> OngoingListBasedPredicateFunction) = { lhs: Expression, rhs: Expression ->
    val x: SymbolicName = org.neo4j.cypherdsl.core.Cypher.name("x")
    factory(x).`in`(lhs).where(x.`in`(rhs))
}

enum class FieldOperator(
        val suffix: String,
        private val conditionCreator: (Expression, Expression) -> Condition,
        val not: Boolean = false,
        val requireParam: Boolean = true,
        val distance: Boolean = false,
        val list: Boolean = false
) {
    EQ("", { lhs, rhs -> lhs.isEqualTo(rhs) });

    fun resolveCondition(
            variablePrefix: String,
            queriedField: String,
            propertyContainer: PropertyContainer,
            field: GraphQLFieldDefinition?,
            value: Any?,
            schemaConfig: SchemaConfig,
            suffix: String? = null
    ): List<Condition> {
        if (schemaConfig.useTemporalScalars && field?.type?.isNeo4jTemporalType() == true) {
            val neo4jTypeConverter = getNeo4jTypeConverter(field)
            val parameter = queryParameter(value, variablePrefix, queriedField, null, suffix)
                .withValue(value)
            return listOf(neo4jTypeConverter.createCondition(propertyContainer.property(field.name), parameter, conditionCreator))
        }
        return if (field?.type?.isNeo4jType() == true && value is Map<*, *>) {
            resolveNeo4jTypeConditions(variablePrefix, queriedField, propertyContainer, field, value, suffix)
        } else if (field?.isNativeId() == true) {
            val id = propertyContainer.id()
            val parameter = queryParameter(value, variablePrefix, queriedField, suffix)
            val condition = if (list) {
                val idVar = CypherDSL.name("id")
                conditionCreator(id, CypherDSL.listWith(idVar).`in`(parameter).returning(CypherDSL.call("toInteger").withArgs(idVar).asFunction()))
            } else {
                conditionCreator(id, CypherDSL.call("toInteger").withArgs(parameter).asFunction())
            }
            listOf(condition)
        } else {
            resolveCondition(variablePrefix, queriedField, propertyContainer.property(field?.propertyName()
                    ?: queriedField), value, suffix)
        }
    }

    private fun resolveNeo4jTypeConditions(variablePrefix: String, queriedField: String, propertyContainer: PropertyContainer, field: GraphQLFieldDefinition, values: Map<*, *>, suffix: String?): List<Condition> {
        val neo4jTypeConverter = getNeo4jTypeConverter(field)
        val conditions = mutableListOf<Condition>()
        if (distance) {
            val parameter = queryParameter(values, variablePrefix, queriedField, suffix)
            conditions += (neo4jTypeConverter as Neo4jPointConverter).createDistanceCondition(
                    propertyContainer.property(field.propertyName()),
                    parameter,
                    conditionCreator
            )
        } else {
            values.entries.forEachIndexed { index, (key, value) ->
                val fieldName = key.toString()
                val parameter = queryParameter(value, variablePrefix, queriedField, if (values.size > 1) "And${index + 1}" else null, suffix, fieldName)
                    .withValue(value)

                conditions += neo4jTypeConverter.createCondition(fieldName, field, parameter, conditionCreator, propertyContainer)
            }
        }
        return conditions
    }

    private fun resolveCondition(variablePrefix: String, queriedField: String, property: Property, value: Any?, suffix: String?): List<Condition> {
        val parameter = queryParameter(value, variablePrefix, queriedField, suffix)
        val condition = conditionCreator(property, parameter)
        return listOf(condition)
    }

    companion object {

        fun forType(type: TypeDefinition<*>, isNeo4jType: Boolean, isList: Boolean): List<FieldOperator> =
                listOf(EQ)
    }

    fun fieldName(fieldName: String) = fieldName + suffix
}

enum class RelationOperator(val suffix: String) {
    SOME("_some"),

    EVERY("_every"),

    SINGLE("_single"),
    NONE("_none"),

    // `eq` if queried with an object, `not exists` if  queried with null
    EQ_OR_NOT_EXISTS(""),
    NOT("_not");

    fun fieldName(fieldName: String) = fieldName + suffix

    fun harmonize(type: GraphQLFieldsContainer, field: GraphQLFieldDefinition, value: Any?, queryFieldName: String) = when (field.type.isList()) {
        true -> when (this) {
            NOT -> when (value) {
                null -> NOT
                else -> NONE
            }
            EQ_OR_NOT_EXISTS -> when (value) {
                null -> EQ_OR_NOT_EXISTS
                else -> {
                    LOGGER.debug("$queryFieldName on type ${type.name} was used for filtering, consider using ${field.name}${EVERY.suffix} instead")
                    EVERY
                }
            }
            else -> this
        }
        false -> when (this) {
            SINGLE -> {
                LOGGER.debug("Using $queryFieldName on type ${type.name} is deprecated, use ${field.name} directly")
                SOME
            }
            SOME -> {
                LOGGER.debug("Using $queryFieldName on type ${type.name} is deprecated, use ${field.name} directly")
                SOME
            }
            NONE -> {
                LOGGER.debug("Using $queryFieldName on type ${type.name} is deprecated, use ${field.name}${NOT.suffix} instead")
                NONE
            }
            NOT -> when (value) {
                null -> NOT
                else -> NONE
            }
            EQ_OR_NOT_EXISTS -> when (value) {
                null -> EQ_OR_NOT_EXISTS
                else -> SOME
            }
            else -> this
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(RelationOperator::class.java)

        fun createRelationFilterFields(type: TypeDefinition<*>, field: FieldDefinition, filterType: String, builder: InputObjectTypeDefinition.Builder) {
            val list = field.type.isList()

            val addFilterField = { op: RelationOperator, description: String ->
                builder.addFilterField(op.fieldName(field.name), false, filterType, description.asDescription())
            }

            addFilterField(EQ_OR_NOT_EXISTS, "Filters only those `${type.name}` for which ${if (list) "all" else "the"} `${field.name}`-relationship matches this filter. " +
                    "If `null` is passed to this field, only those `${type.name}` will be filtered which has no `${field.name}`-relations")

            addFilterField(NOT, "Filters only those `${type.name}` for which ${if (list) "all" else "the"} `${field.name}`-relationship does not match this filter. " +
                    "If `null` is passed to this field, only those `${type.name}` will be filtered which has any `${field.name}`-relation")
            if (list) {
                // n..m
                addFilterField(EVERY, "Filters only those `${type.name}` for which all `${field.name}`-relationships matches this filter")
                addFilterField(SOME, "Filters only those `${type.name}` for which at least one `${field.name}`-relationship matches this filter")
                addFilterField(SINGLE, "Filters only those `${type.name}` for which exactly one `${field.name}`-relationship matches this filter")
                addFilterField(NONE, "Filters only those `${type.name}` for which none of the `${field.name}`-relationships matches this filter")
            } else {
                // n..1
                addFilterField(SINGLE, "@deprecated Use the `${field.name}`-field directly (without any suffix)")
                addFilterField(SOME, "@deprecated Use the `${field.name}`-field directly (without any suffix)")
                addFilterField(NONE, "@deprecated Use the `${field.name}${NOT.suffix}`-field")
            }
        }
    }
}
