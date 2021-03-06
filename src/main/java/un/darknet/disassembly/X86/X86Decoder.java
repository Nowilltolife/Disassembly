package un.darknet.disassembly.X86;

import un.darknet.disassembly.Bits;
import un.darknet.disassembly.GenericOpcode;
import un.darknet.disassembly.PlatformDisassembler;
import un.darknet.disassembly.data.Instruction;
import un.darknet.disassembly.data.InstructionType;
import un.darknet.disassembly.decoding.Decoder;
import un.darknet.disassembly.decoding.DecoderContext;
import un.darknet.disassembly.operand.Operand;
import un.darknet.disassembly.operand.OperandObject;
import un.darknet.disassembly.util.Logging;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static un.darknet.disassembly.X86.Mnemonics.Mnemonics;
import static un.darknet.disassembly.X86.Operations.*;
import static un.darknet.disassembly.operand.Operand.*;

public class X86Decoder extends Decoder {

    Object mnemonic;
    String operation;

    public X86Decoder(PlatformDisassembler platform) {
        super(platform);
    }

    int getSize(boolean reg, DecoderContext ctx) {

        int size = 2; // default: 32-bit
        if (ctx.getFlags().has(PREFIX_LEGACY))
            size = 0; // 8-bit
        if (ctx.getFlags().has(PREFIX_REX) && platform.getBits() >= Bits.BITS_64) // has rex prefix and platform is 64-bit supports 64-bit
            size = 3; // 64-bit
        if (ctx.getFlags().has(PREFIX_OPERAND) && reg) {
            size = 1; // 16-bit
        }
        if(ctx.getFlags().has(PREFIX_ADDRESS) && !reg) {
            size = 1; // 16-bit
        }

        return size;

    }

    long readBytes(DecoderContext ctx) throws IOException {

        int size = getSize(false, ctx);

        if (ctx.getOverride() != null) {

            size = (int) ctx.getOverride();

        }

        long n = 0;
        if (size == 0) n = reader.readByte();
        else if (size == 1) n = reader.readWord();
        else if (size == 2) n = reader.readDword();
        else if (size == 3) n = reader.readQword();

        return n;

    }

    public String decodeRegister(DecoderContext ctx, int reg, int setting) {

        // TODO: make this better
        if (ctx.getFlags().has(SEGMENT_REGISTER_REGRM)) { // reg is a segment register
            ctx.getFlags().unset(SEGMENT_REGISTER_REGRM); // one time flag
            return Constants.SEGMENTS[reg];
        }

        return Constants.REGISTERS[setting][reg];

    }

    /**
     * Decodes the register if it is the last 3 bits of the opcode
     *
     * @param ctx      the decoder context
     * @param operands the operands list
     * @throws IOException if an error occurs
     */
    public void decodeR(DecoderContext ctx, List<Operand> operands) throws IOException {

        int reg = ctx.getOpcode() & 7;

        operands.add(
                new Operand(
                        OperandObject.forRegister(
                                decodeRegister(ctx, reg, getSize(true, ctx))
                        )
                )
        );


    }

    public void decodeRM(DecoderContext ctx, List<Operand> operands) throws IOException {

        int val = reader.readByte();
        int mod = (val & 0xC0) >> 6;
        int rm = (val & 0x07);

        int regSize = getSize(true, ctx);
        int immSize = getSize(false, ctx);

        if (mod == 3) { // rm is a register

            String register = decodeRegister(ctx, rm, regSize);

            operands.add(new Operand(OperandObject.forRegister(register)));

        } else if (mod == 0) {

            String register = decodeRegister(ctx, rm, regSize);

            Operand op = new Operand(OperandObject.forRegister(register));
            op.types.set(TYPE_MEMORY | TYPE_REGISTER);
            operands.add(op);

        }

        if (mod == 1 || mod == 2) { // 8 bit displacement

            long displacement = mod == 1 ? reader.readByte() : reader.readDword();
            String register = decodeRegister(ctx, rm, regSize);

            OperandObject regObj = OperandObject.forRegister(register);
            OperandObject dispObj = OperandObject.forImmediate(displacement);

            Operand op = new Operand(regObj, dispObj);
            op.types.set(TYPE_MEMORY | TYPE_REGISTER | TYPE_CONSTANT);
            operands.add(op);


        }

    }

    public void decodeREGRM(DecoderContext ctx, List<Operand> operands) throws IOException {

        // [opcode]0 0  00  000 000
        //         s d  mod reg r/m

        int val = reader.readByte();

        boolean s = (ctx.getOpcode() & 0x01) == 0x01;
        boolean d = (ctx.getOpcode() & 0x02) == 0x02 || ctx.getFlags().has(IGNORE_DIRECTION_BIT_REGRM);

        int mod = (val & 0xC0) >> 6;
        int reg = (val & 0x38) >> 3;
        int rm = val & 0x07;

        boolean disp = mod == 0 && rm == 5;
        boolean imm = ctx.getFlags().has(REGRM_IMMEDIATE); // has immediate flag

        if (mnemonic instanceof String[]) {

            String[] mnemonics = (String[]) mnemonic;
            mnemonic = mnemonics[reg]; // reg field is the mnemonic

        }

        if (!s) ctx.getFlags().set(PREFIX_LEGACY); // enable legacy mode
        int regSize = getSize(true, ctx);
        int immSize = getSize(false, ctx);

        if (d && !imm) {
            String register = decodeRegister(ctx, reg, regSize);

            operands.add(new Operand(OperandObject.forRegister(register)));
        }

        if (mod == 3) { // rm is a register

            String register = decodeRegister(ctx, rm, regSize);

            operands.add(new Operand(OperandObject.forRegister(register)));

        }

        if (mod == 0 && disp) { // displacement 4 bytes after

            long displacement = reader.readDword();

            Operand op = new Operand(OperandObject.forImmediate(displacement));
            op.types.set(TYPE_MEMORY | TYPE_CONSTANT);

            operands.add(op);


        } else if (mod == 0) {

            String register = decodeRegister(ctx, rm, regSize);

            Operand op = new Operand(OperandObject.forRegister(register));
            op.types.set(TYPE_MEMORY | TYPE_REGISTER);
            operands.add(op);

        }

        if (mod == 1 || mod == 2) { // 8 bit displacement

            long displacement = mod == 1 ? reader.readByte() : reader.readDword();
            String register = decodeRegister(ctx, rm, regSize);

            OperandObject regObj = OperandObject.forRegister(register);
            OperandObject dispObj = OperandObject.forImmediate(displacement);

            Operand op = new Operand(regObj, dispObj);
            op.types.set(TYPE_MEMORY | TYPE_REGISTER | TYPE_CONSTANT);
            operands.add(op);


        }

        if (imm) {

            long immediate = readBytes(ctx);

            OperandObject immObj = OperandObject.forImmediate(immediate);
            Operand op = new Operand(immObj);
            op.types.set(TYPE_CONSTANT);
            operands.add(op);


        }

        if (!d & !imm) {
            String register = decodeRegister(ctx, reg, regSize);

            operands.add(new Operand(OperandObject.forRegister(register)));
        }


    }


    Operand[] decodeOperands(DecoderContext ctx) throws IOException {

        List<Operand> operands = new ArrayList<>();

        for (char c : operation.toCharArray()) {

            if (Character.isDigit(c)) ctx.push(c - '0');
            else
                switch (c) {

                    case 'r': {

                        int reg = ctx.pop();

                        int mode = getSize(true, ctx);

                        String register = Constants.REGISTERS[mode][reg];

                        operands.add(new Operand(OperandObject.forRegister(register)));
                        break;

                    }

                    case 'D': {

                        // print the flag value in binary
                        // print mnemonic and operands and operand object
                        System.out.println("Current Opcode: " + mnemonic + "(0x" + Integer.toHexString(ctx.getOpcode()) + ")");
                        System.out.println("At: " + ctx.getAddress());
                        long flag = ctx.getFlags().backing;
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 64; i++) {
                            sb.append((flag & (1L << i)) == 0 ? '0' : '1');
                        }
                        System.out.println("Flags:\n" + sb);
                        System.out.println("Operation String: " + operation);
                        int i =1;
                        for (Operand op : operands) {
                            System.out.printf("Operand [%d|%s]: %s, objects: %d\n", i, Long.toBinaryString(op.types.backing), op.toString(), op.objects.length);
                            i++;
                        }
                        break;

                    }

                    case 'R':
                        decodeREGRM(ctx, operands);
                        break;
                    case 'm':
                        decodeRM(ctx, operands);
                        break;
                    case 'M':
                        decodeR(ctx, operands);
                        break;

                    case 'i': {

                        int size = getSize(true, ctx);

                        long n = 0;
                        if (size == 0) n = reader.readByte();
                        else if (size == 1) n = reader.readWord();
                        else if (size == 2) n = reader.readDword();
                        else if (size == 3) n = reader.readQword();

                        operands.add(new Operand(OperandObject.forImmediate(n)));
                        break;

                    }

                    case 'U': {

                        int reg = ctx.getOpcode() & 0x07; // extract first 3 bits

                        int size = getSize(true, ctx);

                        String register = Constants.REGISTERS[size][reg];

                        operands.add(new Operand(OperandObject.forRegister(register)));
                        break;

                    }

                    case 'F': {

                        int flagIndex = ctx.pop();
                        long flag = decoderFlags[flagIndex - 1];

                        ctx.getFlags().set(flag);

                        break;


                    }

                    case 'p': {

                        int prefixIndex = ctx.pop();
                        int prefix = x86Prefix[prefixIndex - 1];

                        ctx.getFlags().set(prefix);

                        break;

                    }

                    case 'l': {
                        ctx.getFlags().set(PREFIX_LEGACY);
                        break;
                    }

                    case 'O': {

                        ctx.setOverride(ctx.pop());
                        break;

                    }

                    case 's': {

                        String[] names = (String[]) mnemonic;
                        mnemonic = names[getSize(true, ctx) - 1];

                        break;

                    }

                    case 'h': {

                        // find a handler method for this opcode
                        String methodName = "op" + Integer.toHexString(ctx.getOpcode());
                        try {

                            Method method = getClass().getMethod(methodName, DecoderContext.class, List.class);
                            method.invoke(this, ctx, operands);
                        } catch (NoSuchMethodException e) {
                            throw new RuntimeException("No handler method " + methodName);
                        } catch (InvocationTargetException | IllegalAccessException e) {
                            throw new RuntimeException("Error invoking handler method " + methodName);
                        }

                        break;

                    }

                    default: {
                        Logging.warn("Unhandled operation: " + c);
                    }

                }

        }

        return operands.toArray(new Operand[0]);

    }

    /**
     * Executed when an instruction is a prefix.
     * This will set a prefix flag and then call decode again.
     *
     * @param ctx the current decoder context
     * @throws IOException thrown if an error occurs while reading the stream
     */
    public void decodePrefix(DecoderContext ctx) throws IOException {


        if (!prefixToFlag.containsKey(ctx.getOpcode())) {

            Logging.warn("Unhandled prefix: " + ctx.getOpcode());
            return;

        }

        int flag = Operations.prefixToFlag.get(ctx.getOpcode());
        ctx.getFlags().set(flag);

        int newOpcode = reader.readByte();
        //update context
        ctx.setOpcode(newOpcode);
        ctx.setAddress(ctx.getAddress() + 1);

        decode(ctx);

    }

    void decodeOperation(DecoderContext ctx) {

        int opcode = ctx.getOpcode();

        if (opcode >= Mnemonics.length) {
            mnemonic = "UNKNOWN";
            operation = "";
            return;
        }

        mnemonic = Mnemonics[opcode];

        operation = ops[opcode];


    }

    String getSegment(int segment) {

        return Constants.SEGMENTS[segment];

    }

    boolean isRex(DecoderContext ctx) {

        return ctx.getOpcode() >= 0x40 && ctx.getOpcode() <= 0x4f && platform.getBits() == Bits.BITS_64;

    }

    /**
     * Decode an instruction based on the DecoderContext.
     *
     * @param ctx the decoder context
     */
    @Override
    public void decode(DecoderContext ctx) throws IOException {

        decodeOperation(ctx);

        if (mnemonic.equals("PREFIX") || isRex(ctx)) { // is a prefix

            decodePrefix(ctx);
            return;

        }

        Operand[] operands = decodeOperands(ctx);

        if (ctx.getFlags().has(PREFIX_SEGMENT_OVERRIDE)) {

            // find memory reference operand
            for (Operand operand : operands) {
                if (operand.types.has(TYPE_MEMORY)) {
                    // set segment override

                    String seg = getSegment((int) ctx.getFlags()
                            .mask(SEGMENT_OVERRIDE_MASK)   // xxxx000000000000
                            .shift(SEGMENT_OVERRIDE_SHIFT) // 000000000000xxxx
                            .get());

                    OperandObject segment = OperandObject.forSegment(seg);
                    operand.append(segment);

                }
            }

        }

        long size = stream.getPos() - ctx.getAddress(); // pos - start

        GenericOpcode op = new GenericOpcode((String) mnemonic, size, operands);

        InstructionType type = InstructionType.get((String) mnemonic);

        Instruction instruction = new Instruction(ctx.getAddress(), op, type);

        ctx.setInstruction(instruction); // set the output

    }

    public void op9a(DecoderContext ctx, List<Operand> operands) throws IOException {

        long address = readBytes(ctx);

        int segment = reader.readWord(); // ptr16

        String hex = String.format("0x%X", segment);

        OperandObject segmentObj = OperandObject.forSegment(hex);

        OperandObject offsetObj = OperandObject.forImmediate(address);

        operands.add(new Operand(segmentObj, offsetObj));

    }

    public void opa0(DecoderContext ctx, List<Operand> operands) throws IOException {

        long address = reader.readWord();

        Operand memory = new Operand(OperandObject.forImmediate(address));
        memory.types.set(TYPE_MEMORY);

        Operand register = new Operand(OperandObject.forRegister("AL"));

        operands.add(register);
        operands.add(memory);

    }

    public void opa1(DecoderContext ctx, List<Operand> operands) throws IOException {

        long address = readBytes(ctx); // read actual correct bytes

        Operand memory = new Operand(OperandObject.forImmediate(address));
        memory.types.set(TYPE_MEMORY);

        Operand register = new Operand(OperandObject.forRegister(Constants.REGISTERS[getSize(true, ctx)][0]));

        operands.add(register);
        operands.add(memory);

    }

    public void opa2(DecoderContext ctx, List<Operand> operands) throws IOException {

        long address = reader.readWord();

        Operand memory = new Operand(OperandObject.forImmediate(address));
        memory.types.set(TYPE_MEMORY);

        Operand register = new Operand(OperandObject.forRegister("AL"));

        operands.add(memory);
        operands.add(register);

    }

    public void opa3(DecoderContext ctx, List<Operand> operands) throws IOException {

        long address = readBytes(ctx); // read actual correct bytes

        Operand memory = new Operand(OperandObject.forImmediate(address));
        memory.types.set(TYPE_MEMORY);

        Operand register = new Operand(OperandObject.forRegister(Constants.REGISTERS[getSize(true, ctx)][0]));

        operands.add(memory);
        operands.add(register);

    }



}
