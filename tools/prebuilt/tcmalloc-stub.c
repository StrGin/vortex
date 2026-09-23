/* Stand-in for libtcmalloc_minimal.so.4.
 *
 * The engine loads tcmalloc as an interposing allocator, but under glibc 2.44 on
 * Android it aborts on realloc of pointers the engine passes back ("Attempt to
 * realloc invalid pointer") right when the screenshot path allocates its frame
 * buffer. This stub exports every tcmalloc symbol the engine imports and
 * interposes nothing, so glibc's malloc/free/realloc stay in charge. The malloc
 * hooks simply never fire; the result code they return is ignored by callers.
 */
typedef __SIZE_TYPE__ size_t;

size_t MallocExtension_GetAllocatedSize(const void* p) {
    (void)p;
    return 0;
}

int MallocHook_AddNewHook(void* hook) {
    (void)hook;
    return 0;
}

int MallocHook_RemoveNewHook(void* hook) {
    (void)hook;
    return 0;
}

int MallocHook_AddDeleteHook(void* hook) {
    (void)hook;
    return 0;
}

int MallocHook_RemoveDeleteHook(void* hook) {
    (void)hook;
    return 0;
}
