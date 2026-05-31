package core.mutation

import core.seed.JavaCode

interface Mutator{
    /**
     * Применяет мутации к исходному байткоду
     *
     * @param bytecode исходный байткод Java-класса
     * @param className полное имя мутируемого класса
     * @param packageName имя пакета, в котором находится класс
     * @return мутированный байткод
     */
    fun mutate(bytecode: ByteArray, className: String, packageName: String) : ByteArray

    fun mutate(bytecode: String, className: String, packageName: String) : Pair<ByteArray?, String>
}
